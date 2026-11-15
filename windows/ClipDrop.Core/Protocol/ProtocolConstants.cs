namespace ClipDrop.Core.Protocol;

/// <summary>Constants from docs/PROTOCOL.md §18. Keep the two in sync.</summary>
public static class ProtocolConstants
{
    public const int ProtocolVersion = 1;
    public const int DefaultPort = 47821;
    public const string ServiceType = "_clipdrop._tcp";
    public const string UriScheme = "clipdrop";

    /// <summary>Maximum value of the frame length field: type byte plus payload.</summary>
    public const int MaxFrameLength = 1_048_576;

    /// <summary>Size of the length prefix that precedes every frame.</summary>
    public const int LengthPrefixSize = 4;

    public const int MaxChunkData = 262_144;

    /// <summary>A 16-byte transfer ID followed by a u64 offset.</summary>
    public const int ChunkHeaderSize = 24;

    public const int DataQueueCapacity = 8;

    public const int PairingTokenBytes = 16;
    public const int PairingMaxFailures = 5;
    public const int MaxPairingConnections = 4;
    /// <summary>Echo suppression remembers the last clip exchanged, in either direction (§11.3).</summary>
    public const int EchoHistory = 1;
    public const long MaxClipboardImageBytes = 64L * 1024 * 1024;

    /// <summary>The largest integer every JSON parser represents exactly (2^53 − 1).</summary>
    public const long MaxJsonInteger = 9_007_199_254_740_991L;

    public static readonly TimeSpan HeartbeatIdle = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan HeartbeatTimeout = TimeSpan.FromSeconds(45);
    public static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(3);
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan PairingSessionTimeout = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan PairingTokenTtl = TimeSpan.FromSeconds(120);
    public static readonly TimeSpan OfferResponseTimeout = TimeSpan.FromMinutes(10);
    public static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(100);
}
