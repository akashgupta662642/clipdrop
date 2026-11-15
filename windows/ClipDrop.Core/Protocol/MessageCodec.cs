using System.Text.Json;
using System.Text.Unicode;

namespace ClipDrop.Core.Protocol;

/// <summary>
/// Converts between <see cref="ProtocolMessage"/>s and <see cref="Frame"/>s.
/// Decoding is lenient where PROTOCOL.md §8.2 says receivers must be (field order, whitespace,
/// unknown fields, <c>null</c> optionals, escapes) and strict everywhere else: every decode-time rule
/// of §8.2 raises <see cref="ProtocolException"/> with <see cref="ErrorCode.ProtocolError"/>. Encoding
/// produces the canonical form, which the golden vectors pin byte for byte.
/// </summary>
public static class MessageCodec
{
    private static readonly ProtocolJsonContext Json = ProtocolJsonContext.Default;

    /// <summary>Encodes <paramref name="message"/> canonically.</summary>
    /// <exception cref="ArgumentException">
    /// The message breaks a §8.2 rule or doesn't fit in one frame. Check <see cref="FitsInFrame"/>
    /// first for content of unbounded size, such as clipboard text.
    /// </exception>
    public static Frame Encode(ProtocolMessage message)
    {
        ArgumentNullException.ThrowIfNull(message);
        if (Validate(message) is { } problem)
        {
            throw new ArgumentException($"Invalid {message.GetType().Name}: {problem}", nameof(message));
        }

        return new Frame(TypeCodeOf(message), PayloadBytes(message));
    }

    /// <summary>True if <paramref name="message"/> encodes to a frame within the §7 limit.</summary>
    public static bool FitsInFrame(ProtocolMessage message) =>
        1L + PayloadBytes(message).Length <= ProtocolConstants.MaxFrameLength;

    /// <exception cref="ProtocolException">The payload breaks a rule of §8.2.</exception>
    public static ProtocolMessage Decode(Frame frame)
    {
        ArgumentNullException.ThrowIfNull(frame);
        ProtocolMessage message = frame.Type switch
        {
            null => new UnknownMessage(frame.TypeCode, frame.Payload),
            MessageType.TransferChunk => ChunkHeader.DecodePayload(frame.Payload),
            MessageType type => DecodeJson(type, frame.Payload),
        };

        if (Validate(message) is { } problem)
        {
            throw ProtocolError($"{frame.Type}: {problem}");
        }

        return message;
    }

    public static byte TypeCodeOf(ProtocolMessage message) => message switch
    {
        Hello => (byte)MessageType.Hello,
        HelloAck => (byte)MessageType.HelloAck,
        PairRequest => (byte)MessageType.PairRequest,
        PairResult => (byte)MessageType.PairResult,
        Unpair => (byte)MessageType.Unpair,
        Ping => (byte)MessageType.Ping,
        Pong => (byte)MessageType.Pong,
        ClipText => (byte)MessageType.ClipText,
        TransferOffer => (byte)MessageType.TransferOffer,
        TransferAccept => (byte)MessageType.TransferAccept,
        TransferReject => (byte)MessageType.TransferReject,
        TransferChunk => (byte)MessageType.TransferChunk,
        TransferEnd => (byte)MessageType.TransferEnd,
        TransferResult => (byte)MessageType.TransferResult,
        TransferCancel => (byte)MessageType.TransferCancel,
        ErrorMessage => (byte)MessageType.Error,
        UnknownMessage unknown => unknown.TypeCode,
        _ => throw new ArgumentException($"Unsupported message {message.GetType().Name}", nameof(message)),
    };

    private static byte[] PayloadBytes(ProtocolMessage message) => message switch
    {
        Hello m => JsonSerializer.SerializeToUtf8Bytes(m, Json.Hello),
        HelloAck m => JsonSerializer.SerializeToUtf8Bytes(m, Json.HelloAck),
        PairRequest m => JsonSerializer.SerializeToUtf8Bytes(m, Json.PairRequest),
        PairResult m => JsonSerializer.SerializeToUtf8Bytes(m, Json.PairResult),
        Unpair m => JsonSerializer.SerializeToUtf8Bytes(m, Json.Unpair),
        Ping m => JsonSerializer.SerializeToUtf8Bytes(m, Json.Ping),
        Pong m => JsonSerializer.SerializeToUtf8Bytes(m, Json.Pong),
        ClipText m => JsonSerializer.SerializeToUtf8Bytes(m, Json.ClipText),
        TransferOffer m => JsonSerializer.SerializeToUtf8Bytes(m, Json.TransferOffer),
        TransferAccept m => JsonSerializer.SerializeToUtf8Bytes(m, Json.TransferAccept),
        TransferReject m => JsonSerializer.SerializeToUtf8Bytes(m, Json.TransferReject),
        TransferChunk m => ChunkHeader.EncodePayload(m.TransferId, m.Offset, m.Data.Span),
        TransferEnd m => JsonSerializer.SerializeToUtf8Bytes(m, Json.TransferEnd),
        TransferResult m => JsonSerializer.SerializeToUtf8Bytes(m, Json.TransferResult),
        TransferCancel m => JsonSerializer.SerializeToUtf8Bytes(m, Json.TransferCancel),
        ErrorMessage m => JsonSerializer.SerializeToUtf8Bytes(m, Json.ErrorMessage),
        UnknownMessage m => m.Payload.ToArray(),
        _ => throw new ArgumentException($"Unsupported message {message.GetType().Name}", nameof(message)),
    };

    private static ProtocolMessage DecodeJson(MessageType type, ReadOnlyMemory<byte> payload)
    {
        ReadOnlySpan<byte> json = payload.IsEmpty ? "{}"u8 : payload.Span;
        if (!Utf8.IsValid(json))
        {
            throw ProtocolError($"{type} payload is not valid UTF-8.");
        }

        try
        {
            ProtocolMessage? message = type switch
            {
                MessageType.Hello => JsonSerializer.Deserialize(json, Json.Hello),
                MessageType.HelloAck => JsonSerializer.Deserialize(json, Json.HelloAck),
                MessageType.PairRequest => JsonSerializer.Deserialize(json, Json.PairRequest),
                MessageType.PairResult => JsonSerializer.Deserialize(json, Json.PairResult),
                MessageType.Unpair => JsonSerializer.Deserialize(json, Json.Unpair),
                MessageType.Ping => JsonSerializer.Deserialize(json, Json.Ping),
                MessageType.Pong => JsonSerializer.Deserialize(json, Json.Pong),
                MessageType.ClipText => JsonSerializer.Deserialize(json, Json.ClipText),
                MessageType.TransferOffer => JsonSerializer.Deserialize(json, Json.TransferOffer),
                MessageType.TransferAccept => JsonSerializer.Deserialize(json, Json.TransferAccept),
                MessageType.TransferReject => JsonSerializer.Deserialize(json, Json.TransferReject),
                MessageType.TransferEnd => JsonSerializer.Deserialize(json, Json.TransferEnd),
                MessageType.TransferResult => JsonSerializer.Deserialize(json, Json.TransferResult),
                MessageType.TransferCancel => JsonSerializer.Deserialize(json, Json.TransferCancel),
                MessageType.Error => JsonSerializer.Deserialize(json, Json.ErrorMessage),
                _ => throw new ArgumentOutOfRangeException(nameof(type)),
            };
            return message ?? throw ProtocolError($"{type} payload is not a JSON object.");
        }
        catch (JsonException e)
        {
            throw ProtocolError($"{type} payload doesn't match the message schema.", e);
        }
        catch (InvalidOperationException e)
        {
            // System.Text.Json reports unpaired surrogate escapes this way.
            throw ProtocolError($"{type} payload contains invalid text.", e);
        }
    }

    /// <summary>Returns a description of the first broken rule, or null if the message is valid.</summary>
    internal static string? Validate(ProtocolMessage message) => message switch
    {
        Hello m => CheckHello(m.ProtocolVersion, m.DeviceId, m.DeviceName, m.Platform, m.AppVersion),
        HelloAck m => CheckHello(m.ProtocolVersion, m.DeviceId, m.DeviceName, m.Platform, m.AppVersion),
        PairRequest m => CheckStrings(m.Token),
        PairResult m => CheckReason(m.Ok, m.Reason),
        Unpair or UnknownMessage => null,
        Ping m => m.Nonce is >= 0 and <= Validation.MaxNonce ? null : "nonce out of range",
        Pong m => m.Nonce is >= 0 and <= Validation.MaxNonce ? null : "nonce out of range",
        ClipText m when !Validation.IsUuid(m.ClipId) => "clipId is not a lowercase UUID",
        ClipText m when m.Text.Length == 0 => "text is empty",
        ClipText m when !InJsonRange(m.Timestamp) => "timestamp out of range",
        ClipText m => CheckStrings(m.Text),
        TransferOffer m when !Validation.IsUuid(m.TransferId) => "transferId is not a lowercase UUID",
        TransferOffer m when !InJsonRange(m.Size) => "size out of range",
        TransferOffer m => CheckStrings(m.Purpose, m.Name, m.RelativePath, m.Mime),
        TransferAccept m => CheckTransferId(m.TransferId) ?? (InJsonRange(m.Offset) ? null : "offset out of range"),
        TransferReject m => CheckTransferId(m.TransferId) ?? CheckStrings(m.Reason),
        TransferChunk m => CheckTransferId(m.TransferId) ?? (InJsonRange(m.Offset) ? null : "offset out of range"),
        TransferEnd m => CheckTransferId(m.TransferId)
            ?? (Validation.IsSha256Hex(m.Sha256) ? null : "sha256 is not 64 lowercase hex characters"),
        TransferResult m => CheckTransferId(m.TransferId) ?? CheckReason(m.Ok, m.Reason),
        TransferCancel m => CheckTransferId(m.TransferId),
        ErrorMessage m => CheckStrings(m.Code, m.Message),
        _ => "unsupported message type",
    };

    private static string? CheckHello(int version, string deviceId, string name, string platform, string appVersion) =>
        version < 1 ? "protocolVersion must be at least 1"
        : !Validation.IsDeviceId(deviceId) ? "deviceId is not 43 base64url characters"
        : Validation.NormalizedDeviceName(name) is null ? "deviceName is empty"
        : CheckStrings(name, platform, appVersion);

    private static string? CheckReason(bool ok, string? reason) => (ok, reason) switch
    {
        (true, not null) => "reason must be absent when ok is true",
        (false, null) => "reason is required when ok is false",
        (_, not null) => CheckStrings(reason),
        _ => null,
    };

    private static string? CheckTransferId(string id) =>
        Validation.IsUuid(id) ? null : "transferId is not a lowercase UUID";

    private static string? CheckStrings(params ReadOnlySpan<string> values)
    {
        foreach (string value in values)
        {
            if (Validation.HasUnpairedSurrogate(value))
            {
                return "a string contains an unpaired surrogate";
            }
        }

        return null;
    }

    private static bool InJsonRange(long value) => value is >= 0 and <= ProtocolConstants.MaxJsonInteger;

    private static ProtocolException ProtocolError(string message, Exception? inner = null) =>
        new(ErrorCode.ProtocolError, message, inner);
}
