using ClipDrop.Core.Protocol;

namespace ClipDrop.Core.Tests.Protocol;

public class MessageTypeTests
{
    /// <summary>The message table from docs/PROTOCOL.md §8.1.</summary>
    public static TheoryData<MessageType, byte> SpecTable => new()
    {
        { MessageType.Hello, 0x01 },
        { MessageType.HelloAck, 0x02 },
        { MessageType.PairRequest, 0x03 },
        { MessageType.PairResult, 0x04 },
        { MessageType.Unpair, 0x05 },
        { MessageType.Ping, 0x10 },
        { MessageType.Pong, 0x11 },
        { MessageType.ClipText, 0x20 },
        { MessageType.TransferOffer, 0x30 },
        { MessageType.TransferAccept, 0x31 },
        { MessageType.TransferReject, 0x32 },
        { MessageType.TransferChunk, 0x33 },
        { MessageType.TransferEnd, 0x34 },
        { MessageType.TransferResult, 0x35 },
        { MessageType.TransferCancel, 0x36 },
        { MessageType.Error, 0x7F },
    };

    [Theory]
    [MemberData(nameof(SpecTable))]
    public void Codes_match_the_message_table_in_the_spec(MessageType type, byte code)
    {
        Assert.Equal(code, (byte)type);
        Assert.True(MessageTypes.IsDefined(code));
    }

    [Fact]
    public void Every_type_appears_in_the_spec_table()
    {
        Assert.Equal(Enum.GetValues<MessageType>().Length, SpecTable.Count);
    }

    [Theory]
    [InlineData(0x00)]
    [InlineData(0x06)]
    [InlineData(0x12)]
    [InlineData(0x21)]
    [InlineData(0x37)]
    [InlineData(0x7E)]
    [InlineData(0x80)]
    [InlineData(0xFF)]
    public void Unknown_codes_are_not_defined(byte code)
    {
        Assert.False(MessageTypes.IsDefined(code));
    }

    [Fact]
    public void Only_TransferChunk_is_binary()
    {
        var binary = Enum.GetValues<MessageType>().Where(t => t.GetPayloadKind() == PayloadKind.Binary);
        Assert.Equal([MessageType.TransferChunk], binary);
    }

    [Fact]
    public void Only_pairing_frames_and_Error_are_allowed_before_pairing()
    {
        var allowed = Enum.GetValues<MessageType>().Where(t => t.IsAllowedBeforePairing()).ToHashSet();
        Assert.Equal(
            [MessageType.Hello, MessageType.HelloAck, MessageType.PairRequest, MessageType.PairResult, MessageType.Error],
            allowed.Order());
    }

    [Fact]
    public void The_largest_chunk_frame_fits_within_the_frame_limit()
    {
        const int largestChunkFrame = 1 + ProtocolConstants.ChunkHeaderSize + ProtocolConstants.MaxChunkData;
        Assert.True(largestChunkFrame <= ProtocolConstants.MaxFrameLength);
    }
}
