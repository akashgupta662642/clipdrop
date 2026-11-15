namespace ClipDrop.Core.Protocol;

/// <summary>Error codes carried by the ERROR message (PROTOCOL.md §14).</summary>
public enum ErrorCode
{
    UnknownType,
    ProtocolError,
    FrameTooLarge,
    UnsupportedVersion,
    NotPaired,
    Internal,
}

public static class ErrorCodes
{
    public static string ToWireName(this ErrorCode code) => code switch
    {
        ErrorCode.UnknownType => "unknown_type",
        ErrorCode.ProtocolError => "protocol_error",
        ErrorCode.FrameTooLarge => "frame_too_large",
        ErrorCode.UnsupportedVersion => "unsupported_version",
        ErrorCode.NotPaired => "not_paired",
        ErrorCode.Internal => "internal",
        _ => throw new ArgumentOutOfRangeException(nameof(code)),
    };

    /// <summary>Unknown codes are treated like <c>protocol_error</c> (§14).</summary>
    public static ErrorCode FromWireName(string name) => name switch
    {
        "unknown_type" => ErrorCode.UnknownType,
        "frame_too_large" => ErrorCode.FrameTooLarge,
        "unsupported_version" => ErrorCode.UnsupportedVersion,
        "not_paired" => ErrorCode.NotPaired,
        "internal" => ErrorCode.Internal,
        _ => ErrorCode.ProtocolError,
    };
}

/// <summary>
/// The peer broke the protocol. The session sends ERROR with <see cref="Code"/> and, unless the code
/// is <see cref="ErrorCode.UnknownType"/>, closes the connection. The message must never contain
/// clipboard contents, file contents, tokens or key material, because it may be sent and logged.
/// </summary>
public sealed class ProtocolException : IOException
{
    public ProtocolException(ErrorCode code, string message, Exception? innerException = null)
        : base(message, innerException)
    {
        Code = code;
    }

    public ErrorCode Code { get; }
}

/// <summary>The stream ended in the middle of a frame (as opposed to cleanly between frames).</summary>
public sealed class TruncatedFrameException(string message) : EndOfStreamException(message);
