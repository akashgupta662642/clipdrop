using ClipDrop.Core.Protocol;

namespace ClipDrop.Core.Tests.Protocol;

public class FrameCodecTests
{
    [Fact]
    public async Task Reads_consecutive_frames_then_reports_a_clean_end_of_stream()
    {
        var first = new Frame((byte)MessageType.Ping, """{"nonce":1}"""u8.ToArray());
        var second = new Frame(0x42, new byte[] { 1, 2, 3 });
        using var input = new MemoryStream([.. FrameCodec.Encode(first), .. FrameCodec.Encode(second)]);
        CancellationToken ct = TestContext.Current.CancellationToken;

        Assert.Equal(first, await FrameCodec.ReadFrameAsync(input, ct));
        Assert.Equal(second, await FrameCodec.ReadFrameAsync(input, ct));
        var end = await Assert.ThrowsAsync<EndOfStreamException>(async () => await FrameCodec.ReadFrameAsync(input, ct));
        Assert.IsNotType<TruncatedFrameException>(end);
    }

    [Fact]
    public async Task Handles_a_stream_that_returns_one_byte_per_read()
    {
        var frame = new Frame((byte)MessageType.ClipText, Enumerable.Range(0, 1000).Select(i => (byte)i).ToArray());
        using var trickle = new TrickleStream(FrameCodec.Encode(frame));
        Assert.Equal(frame, await FrameCodec.ReadFrameAsync(trickle, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task Rejects_an_oversized_length_before_reading_the_body()
    {
        using var hostile = new HeaderOnlyStream([0x7F, 0xFF, 0xFF, 0xFF]);
        var error = await Assert.ThrowsAsync<ProtocolException>(
            async () => await FrameCodec.ReadFrameAsync(hostile, TestContext.Current.CancellationToken));
        Assert.Equal(ErrorCode.FrameTooLarge, error.Code);
        Assert.Equal(4, hostile.Position);
    }

    [Fact]
    public async Task Round_trips_a_frame_at_the_maximum_length()
    {
        var frame = new Frame((byte)MessageType.TransferChunk, new byte[ProtocolConstants.MaxFrameLength - 1]);
        using var stream = new MemoryStream();
        await FrameCodec.WriteFrameAsync(stream, frame, TestContext.Current.CancellationToken);
        Assert.Equal(ProtocolConstants.LengthPrefixSize + ProtocolConstants.MaxFrameLength, stream.Length);

        stream.Position = 0;
        Assert.Equal(frame, await FrameCodec.ReadFrameAsync(stream, TestContext.Current.CancellationToken));
    }

    [Fact]
    public void Refuses_to_build_a_frame_above_the_limit()
    {
        Assert.Throws<ArgumentException>(() => new Frame(1, new byte[ProtocolConstants.MaxFrameLength]));
    }

    /// <summary>Serves at most one byte per read call.</summary>
    private sealed class TrickleStream(byte[] data) : MemoryStream(data)
    {
        public override int Read(byte[] buffer, int offset, int count) => base.Read(buffer, offset, Math.Min(count, 1));

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            base.ReadAsync(buffer[..Math.Min(buffer.Length, 1)], cancellationToken);
    }

    /// <summary>Serves only the given header bytes and fails the test if the codec reads further.</summary>
    private sealed class HeaderOnlyStream(byte[] header) : MemoryStream(header)
    {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            Assert.True(Position < Length, "codec read past the length field");
            return base.ReadAsync(buffer, cancellationToken);
        }
    }
}

public class MessageCodecTests
{
    private const string TransferId = "a3bb189e-8bf9-4888-9912-ace4e6543002";
    private const string ClipId = "3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162";

    public static TheoryData<ProtocolMessage> ValidMessages
    {
        get
        {
            string deviceId = DeviceIds.FromFingerprint(Enumerable.Range(0, 32).Select(i => (byte)i).ToArray());
            return
            [
                new Hello(1, deviceId, "Pixel", "android", "0.1.0"),
                new HelloAck(1, deviceId, "PC", "windows", "0.1.0", Paired: false),
                new PairRequest("AAECAwQFBgcICQoLDA0ODw"),
                new PairResult(Ok: false, Reason: "invalid_token"),
                Unpair.Instance,
                new Ping(Validation.MaxNonce),
                new Pong(0),
                new ClipText(ClipId, "hi", 1),
                new TransferOffer(TransferId, TransferOffer.PurposeFile, "a.txt", "", 3, "text/plain"),
                new TransferAccept(TransferId, 0),
                new TransferReject(TransferId, "declined"),
                new TransferChunk(TransferId, 9, new byte[] { 1, 2, 3 }),
                new TransferEnd(TransferId, new string('0', 64)),
                new TransferResult(TransferId, Ok: true),
                new TransferCancel(TransferId),
                new ErrorMessage("internal", "boom"),
                new UnknownMessage(0x55, new byte[] { 9 }),
            ];
        }
    }

    public static TheoryData<ProtocolMessage> InvalidMessages =>
    [
        new ClipText(ClipId.ToUpperInvariant(), "x", 1),
        new ClipText(ClipId, "", 1),
        new ClipText(ClipId, "lone \uD800", 1),
        new Ping(-1),
        new Ping(Validation.MaxNonce + 1),
        new PairResult(Ok: true, Reason: "expired_token"),
        new PairResult(Ok: false),
        new TransferAccept(TransferId, ProtocolConstants.MaxJsonInteger + 1),
        new TransferEnd(TransferId, new string('A', 64)),
        new Hello(0, new string('x', 43), "n", "android", "1"),
        new Hello(1, "too-short", "n", "android", "1"),
        new Hello(1, new string('x', 43), "   ", "android", "1"),
    ];

    [Theory]
    [MemberData(nameof(ValidMessages))]
    public void Every_message_round_trips(ProtocolMessage message)
    {
        Assert.Equal(message, MessageCodec.Decode(MessageCodec.Encode(message)));
    }

    [Theory]
    [MemberData(nameof(InvalidMessages))]
    public void Encoding_rejects_messages_that_break_the_decode_rules(ProtocolMessage message)
    {
        Assert.Throws<ArgumentException>(() => MessageCodec.Encode(message));
    }

    [Fact]
    public void FitsInFrame_finds_the_largest_clip_that_can_be_sent()
    {
        int overhead = MessageCodec.Encode(new ClipText(ClipId, "x", 1)).Payload.Length - 1;
        int maxText = ProtocolConstants.MaxFrameLength - 1 - overhead;
        Assert.True(MessageCodec.FitsInFrame(new ClipText(ClipId, new string('x', maxText), 1)));
        Assert.False(MessageCodec.FitsInFrame(new ClipText(ClipId, new string('x', maxText + 1), 1)));
        // Characters that need escaping count at their escaped size.
        Assert.False(MessageCodec.FitsInFrame(new ClipText(ClipId, new string('\n', (maxText / 2) + 1), 1)));
    }

    [Fact]
    public void ToString_never_reveals_clipboard_text_or_pairing_tokens()
    {
        Assert.DoesNotContain("secret", new ClipText(ClipId, "my secret password", 1).ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("AAECAwQF", new PairRequest("AAECAwQFBgcICQoLDA0ODw").ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("SecretTokenValue", MakeUri("SecretTokenValue123456").ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void Chunk_header_puts_the_uuid_in_network_byte_order_then_a_big_endian_offset()
    {
        byte[] payload = ChunkHeader.EncodePayload("00112233-4455-6677-8899-aabbccddeeff", 0x0013040506070809L, [0x7F]);
        Assert.Equal("00112233445566778899aabbccddeeff" + "0013040506070809" + "7f", Convert.ToHexStringLower(payload));
    }

    [Fact]
    public void Chunk_data_must_be_between_1_byte_and_256_kib()
    {
        const string id = "00112233-4455-6677-8899-aabbccddeeff";
        Assert.Equal(ChunkHeader.Size + ProtocolConstants.MaxChunkData, ChunkHeader.EncodePayload(id, 0, new byte[ProtocolConstants.MaxChunkData]).Length);
        Assert.Throws<ArgumentException>(() => ChunkHeader.EncodePayload(id, 0, []));
        Assert.Throws<ArgumentException>(() => ChunkHeader.EncodePayload(id, 0, new byte[ProtocolConstants.MaxChunkData + 1]));
    }

    [Theory]
    [InlineData("Office PC")]
    [InlineData("Akash’s PC 💻")]
    [InlineData("a+b & c=d")]
    [InlineData("100% done")]
    [InlineData("日本語")]
    public void Pairing_uris_round_trip_any_device_name(string name)
    {
        PairingUri uri = MakeUri("BBBBBBBBBBBBBBBBBBBBBB", name);
        PairingUri parsed = Assert.IsType<PairingUriParseResult.Valid>(PairingUri.Parse(uri.ToUriString())).Uri;
        Assert.Equal(name, parsed.Name);
        Assert.Equal(uri.Hosts, parsed.Hosts);
    }

    private static PairingUri MakeUri(string token, string name = "PC") =>
        new(["192.168.1.20", "10.0.0.7"], 47821, new string('A', 43), token, name);
}
