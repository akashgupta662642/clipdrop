package io.github.akashgupta662642.clipdrop.protocol

import java.io.EOFException
import java.io.IOException

/** Error codes carried by the ERROR message (PROTOCOL.md §14). */
public enum class ErrorCode(public val wireName: String) {
    UNKNOWN_TYPE("unknown_type"),
    PROTOCOL_ERROR("protocol_error"),
    FRAME_TOO_LARGE("frame_too_large"),
    UNSUPPORTED_VERSION("unsupported_version"),
    NOT_PAIRED("not_paired"),
    INTERNAL("internal"),
    ;

    public companion object {
        /** Unknown codes are treated like `protocol_error` (§14). */
        public fun fromWireName(name: String): ErrorCode =
            entries.firstOrNull { it.wireName == name } ?: PROTOCOL_ERROR
    }
}

/**
 * The peer broke the protocol. The session sends `ERROR` with [code] and, unless the code is
 * [ErrorCode.UNKNOWN_TYPE], closes the connection. [message] must never contain clipboard
 * contents, file contents, tokens or key material, because it may be sent to the peer and logged.
 */
public class ProtocolException(
    public val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The stream ended in the middle of a frame (as opposed to cleanly between frames). */
public class TruncatedFrameException(message: String) : EOFException(message)
