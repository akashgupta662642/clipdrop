using System.Text;
using System.Text.Json;
using ClipDrop.Core.Protocol;

namespace ClipDrop.Core.Tests.Protocol;

/// <summary>Runs every vector in docs/test-vectors (PROTOCOL.md §16). The Kotlin suite runs the same files.</summary>
public class GoldenVectorTests
{
    public static TheoryData<string> Frames => TestVectors.Names("frames.json");

    public static TheoryData<string> InvalidFrames => TestVectors.Names("invalid-frames.json");

    public static TheoryData<string> PairingUris => TestVectors.Names("pairing-uri.json");

    [Theory]
    [MemberData(nameof(Frames))]
    public async Task Positive_frames_decode_and_reencode_byte_for_byte(string name)
    {
        JsonElement vector = TestVectors.Vector("frames.json", name);
        using var input = new MemoryStream(Convert.FromHexString(vector.GetProperty("hex").GetString()!));

        Frame frame = await FrameCodec.ReadFrameAsync(input, TestContext.Current.CancellationToken);
        Assert.Equal(input.Length, input.Position);
        Assert.Equal(vector.GetProperty("typeCode").GetInt32(), frame.TypeCode);

        ProtocolMessage decoded = MessageCodec.Decode(frame);
        Assert.Equal(ExpectedMessage(vector), decoded);

        string reencoded = Convert.ToHexStringLower(FrameCodec.Encode(MessageCodec.Encode(decoded)));
        Assert.Equal(vector.OptionalString("reencodedHex") ?? vector.GetProperty("hex").GetString(), reencoded);
    }

    [Theory]
    [MemberData(nameof(InvalidFrames))]
    public async Task Negative_frames_fail_with_the_expected_error(string name)
    {
        JsonElement vector = TestVectors.Vector("invalid-frames.json", name);
        using var input = new MemoryStream(Convert.FromHexString(vector.GetProperty("hex").GetString()!));
        CancellationToken ct = TestContext.Current.CancellationToken;

        switch (vector.GetProperty("error").GetString())
        {
            case "frame_too_large":
                var tooLarge = await Assert.ThrowsAsync<ProtocolException>(async () => await FrameCodec.ReadFrameAsync(input, ct));
                Assert.Equal(ErrorCode.FrameTooLarge, tooLarge.Code);
                break;
            case "truncated":
                await Assert.ThrowsAsync<TruncatedFrameException>(async () => await FrameCodec.ReadFrameAsync(input, ct));
                break;
            case "protocol_error":
                Frame frame = await FrameCodec.ReadFrameAsync(input, ct);
                Assert.Equal(input.Length, input.Position);
                var error = Assert.Throws<ProtocolException>(() => MessageCodec.Decode(frame));
                Assert.Equal(ErrorCode.ProtocolError, error.Code);
                break;
            default:
                Assert.Fail("Unknown expected error");
                break;
        }
    }

    [Theory]
    [MemberData(nameof(PairingUris))]
    public void Pairing_uris_parse_as_specified(string name)
    {
        JsonElement vector = TestVectors.Vector("pairing-uri.json", name);
        PairingUriParseResult result = PairingUri.Parse(vector.GetProperty("uri").GetString()!);

        switch (vector.OptionalString("error"))
        {
            case null:
                JsonElement expected = vector.GetProperty("expected");
                PairingUri uri = Assert.IsType<PairingUriParseResult.Valid>(result).Uri;
                Assert.Equal(PairingUri.Version, expected.GetProperty("version").GetInt32());
                Assert.Equal(expected.GetProperty("hosts").EnumerateArray().Select(h => h.GetString()), uri.Hosts);
                Assert.Equal(expected.GetProperty("port").GetInt32(), uri.Port);
                Assert.Equal(expected.GetProperty("fingerprint").GetString(), uri.Fingerprint);
                Assert.Equal(expected.GetProperty("token").GetString(), uri.Token);
                Assert.Equal(expected.GetProperty("name").GetString(), uri.Name);

                // What we build, we must parse back to the same value.
                PairingUri reparsed = Assert.IsType<PairingUriParseResult.Valid>(PairingUri.Parse(uri.ToUriString())).Uri;
                Assert.Equal(uri.ToUriString(), reparsed.ToUriString());
                break;
            case "invalid":
                Assert.IsType<PairingUriParseResult.Invalid>(result);
                break;
            case "unsupported_version":
                Assert.IsType<PairingUriParseResult.UnsupportedVersion>(result);
                break;
            default:
                Assert.Fail("Unknown expected error");
                break;
        }
    }

    [Fact]
    public void Device_ids_derive_from_the_certificate_fingerprint()
    {
        JsonElement identity = TestVectors.File("identity.json");
        byte[] der = Convert.FromHexString(identity.GetProperty("certificateDerHex").GetString()!);
        Assert.Equal(identity.GetProperty("fingerprintHex").GetString(), Convert.ToHexStringLower(DeviceIds.Fingerprint(der)));
        Assert.Equal(identity.GetProperty("deviceId").GetString(), DeviceIds.FromCertificate(der));

        var entries = identity.GetProperty("extraDeviceIds").EnumerateArray()
            .Select(e => (Fingerprint: e.GetProperty("fingerprintHex").GetString()!, Id: e.GetProperty("deviceId").GetString()!))
            .Append((identity.GetProperty("fingerprintHex").GetString()!, identity.GetProperty("deviceId").GetString()!));
        foreach ((string fingerprintHex, string id) in entries)
        {
            byte[] fingerprint = Convert.FromHexString(fingerprintHex);
            Assert.Equal(id, DeviceIds.FromFingerprint(fingerprint));
            Assert.Equal(fingerprint, DeviceIds.ToFingerprint(id));
        }
    }

    private static ProtocolMessage ExpectedMessage(JsonElement vector)
    {
        JsonElement message = vector.GetProperty("message");
        byte typeCode = (byte)vector.GetProperty("typeCode").GetInt32();
        return vector.GetProperty("type").GetString() switch
        {
            "UNKNOWN" => new UnknownMessage(typeCode, Convert.FromHexString(message.GetProperty("payloadHex").GetString()!)),
            "TRANSFER_CHUNK" => new TransferChunk(
                message.GetProperty("transferId").GetString()!,
                message.GetProperty("offset").GetInt64(),
                Convert.FromHexString(message.GetProperty("dataHex").GetString()!)),
            _ => MessageCodec.Decode(new Frame(typeCode, Encoding.UTF8.GetBytes(message.GetRawText()))),
        };
    }
}
