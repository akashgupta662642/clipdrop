using System.Text;

namespace ClipDrop.Core.Protocol;

// Every message in PROTOCOL.md §8.1. Constructor parameter order is the canonical field order of
// §8.2, so don't reorder parameters. JSON field names are the camelCase property names.

/// <summary>Base type of every protocol message.</summary>
public abstract record ProtocolMessage;

public sealed record Hello(int ProtocolVersion, string DeviceId, string DeviceName, string Platform, string AppVersion)
    : ProtocolMessage;

public sealed record HelloAck(int ProtocolVersion, string DeviceId, string DeviceName, string Platform, string AppVersion, bool Paired)
    : ProtocolMessage;

public sealed record PairRequest(string Token) : ProtocolMessage
{
    protected override bool PrintMembers(StringBuilder builder)
    {
        ArgumentNullException.ThrowIfNull(builder);
        builder.Append("Token = <redacted>");
        return true;
    }
}

public sealed record PairResult(bool Ok, string? Reason = null) : ProtocolMessage;

public sealed record Unpair : ProtocolMessage
{
    public static Unpair Instance { get; } = new();
}

public sealed record Ping(long Nonce) : ProtocolMessage;

public sealed record Pong(long Nonce) : ProtocolMessage;

public sealed record ClipText(string ClipId, string Text, long Timestamp) : ProtocolMessage
{
    protected override bool PrintMembers(StringBuilder builder)
    {
        ArgumentNullException.ThrowIfNull(builder);
        builder.Append(System.Globalization.CultureInfo.InvariantCulture, $"ClipId = {ClipId}, Text = <{Text.Length} chars>, Timestamp = {Timestamp}");
        return true;
    }
}

public sealed record TransferOffer(string TransferId, string Purpose, string Name, string RelativePath, long Size, string Mime)
    : ProtocolMessage
{
    public const string PurposeFile = "file";
    public const string PurposeClipboardImage = "clipboardImage";
}

public sealed record TransferAccept(string TransferId, long Offset) : ProtocolMessage;

public sealed record TransferReject(string TransferId, string Reason) : ProtocolMessage;

/// <summary>Binary frame (§8.1): a chunk header followed by <see cref="Data"/>.</summary>
public sealed record TransferChunk(string TransferId, long Offset, ReadOnlyMemory<byte> Data) : ProtocolMessage
{
    public bool Equals(TransferChunk? other) =>
        other is not null && other.TransferId == TransferId && other.Offset == Offset && other.Data.Span.SequenceEqual(Data.Span);

    public override int GetHashCode() => HashCode.Combine(TransferId, Offset, Data.Length);

    protected override bool PrintMembers(StringBuilder builder)
    {
        ArgumentNullException.ThrowIfNull(builder);
        builder.Append(System.Globalization.CultureInfo.InvariantCulture, $"TransferId = {TransferId}, Offset = {Offset}, Data = {Data.Length} bytes");
        return true;
    }
}

public sealed record TransferEnd(string TransferId, string Sha256) : ProtocolMessage;

public sealed record TransferResult(string TransferId, bool Ok, string? Reason = null) : ProtocolMessage;

public sealed record TransferCancel(string TransferId) : ProtocolMessage;

/// <summary>The ERROR message (§14).</summary>
public sealed record ErrorMessage(string Code, string Message) : ProtocolMessage;

/// <summary>A frame whose type this version doesn't define (§15.2).</summary>
public sealed record UnknownMessage(byte TypeCode, ReadOnlyMemory<byte> Payload) : ProtocolMessage
{
    public bool Equals(UnknownMessage? other) =>
        other is not null && other.TypeCode == TypeCode && other.Payload.Span.SequenceEqual(Payload.Span);

    public override int GetHashCode() => HashCode.Combine(TypeCode, Payload.Length);
}
