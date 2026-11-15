package io.github.akashgupta662642.clipdrop.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Constants from docs/PROTOCOL.md §18. Keep the two in sync. */
public object ProtocolConstants {
    public const val PROTOCOL_VERSION: Int = 1
    public const val DEFAULT_PORT: Int = 47821
    public const val SERVICE_TYPE: String = "_clipdrop._tcp"
    public const val URI_SCHEME: String = "clipdrop"

    /** Maximum value of the frame length field: type byte plus payload. */
    public const val MAX_FRAME_LENGTH: Int = 1_048_576

    /** Size of the length prefix that precedes every frame. */
    public const val LENGTH_PREFIX_SIZE: Int = 4

    public const val MAX_CHUNK_DATA: Int = 262_144

    /** A 16-byte transfer ID followed by a u64 offset. */
    public const val CHUNK_HEADER_SIZE: Int = 24

    public const val DATA_QUEUE_CAPACITY: Int = 8

    public const val PAIRING_TOKEN_BYTES: Int = 16
    public const val PAIRING_MAX_FAILURES: Int = 5
    public const val MAX_PAIRING_CONNECTIONS: Int = 4
    /** Echo suppression remembers the last clip exchanged, in either direction (§11.3). */
    public const val ECHO_HISTORY: Int = 1
    public const val MAX_CLIPBOARD_IMAGE_BYTES: Long = 64L * 1024 * 1024

    /** The largest integer every JSON parser represents exactly (2^53 − 1). */
    public const val MAX_JSON_INTEGER: Long = 9_007_199_254_740_991L

    public val HEARTBEAT_IDLE: Duration = 15.seconds
    public val HEARTBEAT_TIMEOUT: Duration = 45.seconds
    public val CONNECT_TIMEOUT: Duration = 3.seconds
    public val HANDSHAKE_TIMEOUT: Duration = 10.seconds
    public val PAIRING_SESSION_TIMEOUT: Duration = 30.seconds
    public val PAIRING_TOKEN_TTL: Duration = 120.seconds
    public val RECONNECT_BACKOFF_INITIAL: Duration = 1.seconds
    public val RECONNECT_BACKOFF_MAX: Duration = 30.seconds
    public val OFFER_RESPONSE_TIMEOUT: Duration = 10.minutes
    public val PROGRESS_INTERVAL: Duration = 100.milliseconds
}
