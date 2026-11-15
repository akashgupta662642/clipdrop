namespace ClipDrop.Core.Protocol;

/// <summary>
/// One frame on the wire (PROTOCOL.md §7): a type code and its payload. <see cref="TypeCode"/> may be
/// a code this version doesn't define; such frames are still valid frames (§15.2).
/// </summary>
public sealed class Frame : IEquatable<Frame>
{
    public Frame(byte typeCode, ReadOnlyMemory<byte> payload)
    {
        if (payload.Length > ProtocolConstants.MaxFrameLength - 1)
        {
            throw new ArgumentException($"Payload of {payload.Length} bytes exceeds the frame limit.", nameof(payload));
        }

        TypeCode = typeCode;
        Payload = payload;
    }

    public byte TypeCode { get; }

    public ReadOnlyMemory<byte> Payload { get; }

    /// <summary>The message type, or null if this protocol version doesn't define <see cref="TypeCode"/>.</summary>
    public MessageType? Type => MessageTypes.IsDefined(TypeCode) ? (MessageType)TypeCode : null;

    /// <summary>Value of the frame's length field: the type byte plus the payload.</summary>
    public int Length => 1 + Payload.Length;

    public bool Equals(Frame? other) =>
        other is not null && other.TypeCode == TypeCode && other.Payload.Span.SequenceEqual(Payload.Span);

    public override bool Equals(object? obj) => Equals(obj as Frame);

    public override int GetHashCode() => HashCode.Combine(TypeCode, Payload.Length);

    public override string ToString() =>
        $"Frame(type={(object?)Type ?? $"0x{TypeCode:x2}"}, payload={Payload.Length} bytes)";
}
