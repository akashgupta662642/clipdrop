namespace ClipDrop.Core.Protocol;

/// <summary>Every frame type in docs/PROTOCOL.md §8.1, with its wire code.</summary>
#pragma warning disable CA1028 // The wire format defines the type as one byte.
public enum MessageType : byte
#pragma warning restore CA1028
{
    Hello = 0x01,
    HelloAck = 0x02,
    PairRequest = 0x03,
    PairResult = 0x04,
    Unpair = 0x05,
    Ping = 0x10,
    Pong = 0x11,
    ClipText = 0x20,
    TransferOffer = 0x30,
    TransferAccept = 0x31,
    TransferReject = 0x32,
    TransferChunk = 0x33,
    TransferEnd = 0x34,
    TransferResult = 0x35,
    TransferCancel = 0x36,
    Error = 0x7F,
}

/// <summary>How a frame's payload is encoded (PROTOCOL.md §7).</summary>
public enum PayloadKind
{
    Json,
    Binary,
}

public static class MessageTypes
{
    /// <summary>
    /// True if <paramref name="code"/> is a type this protocol version defines.
    /// Unknown types get an <c>unknown_type</c> error and are otherwise ignored (§15.2).
    /// </summary>
    public static bool IsDefined(byte code) => Enum.IsDefined((MessageType)code);

    public static PayloadKind GetPayloadKind(this MessageType type) =>
        type == MessageType.TransferChunk ? PayloadKind.Binary : PayloadKind.Json;

    /// <summary>True for the frames allowed before pairing completes (§9.1).</summary>
    public static bool IsAllowedBeforePairing(this MessageType type) => type is
        MessageType.Hello or MessageType.HelloAck or MessageType.PairRequest or
        MessageType.PairResult or MessageType.Error;
}
