package io.github.akashgupta662642.clipdrop.protocol

import kotlinx.serialization.Serializable

/**
 * Every message in PROTOCOL.md §8.1. Property order is the canonical field order of §8.2, so
 * don't reorder properties. JSON field names are the property names.
 */
public sealed interface Message {
    public val typeCode: Int
}

/** Messages this protocol version defines. */
public sealed interface KnownMessage : Message {
    public val type: MessageType
    override val typeCode: Int get() = type.code
}

@Serializable
public data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
) : KnownMessage {
    override val type: MessageType get() = MessageType.HELLO
}

@Serializable
public data class HelloAck(
    val protocolVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val paired: Boolean,
) : KnownMessage {
    override val type: MessageType get() = MessageType.HELLO_ACK
}

@Serializable
public data class PairRequest(val token: String) : KnownMessage {
    override val type: MessageType get() = MessageType.PAIR_REQUEST
    override fun toString(): String = "PairRequest(token=<redacted>)"
}

@Serializable
public data class PairResult(val ok: Boolean, val reason: String? = null) : KnownMessage {
    override val type: MessageType get() = MessageType.PAIR_RESULT
}

@Serializable
public data object Unpair : KnownMessage {
    override val type: MessageType get() = MessageType.UNPAIR
}

@Serializable
public data class Ping(val nonce: Long) : KnownMessage {
    override val type: MessageType get() = MessageType.PING
}

@Serializable
public data class Pong(val nonce: Long) : KnownMessage {
    override val type: MessageType get() = MessageType.PONG
}

@Serializable
public data class ClipText(val clipId: String, val text: String, val timestamp: Long) : KnownMessage {
    override val type: MessageType get() = MessageType.CLIP_TEXT
    override fun toString(): String = "ClipText(clipId=$clipId, text=<${text.length} chars>, timestamp=$timestamp)"
}

@Serializable
public data class TransferOffer(
    val transferId: String,
    val purpose: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mime: String,
) : KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_OFFER

    public companion object {
        public const val PURPOSE_FILE: String = "file"
        public const val PURPOSE_CLIPBOARD_IMAGE: String = "clipboardImage"
    }
}

@Serializable
public data class TransferAccept(val transferId: String, val offset: Long) : KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_ACCEPT
}

@Serializable
public data class TransferReject(val transferId: String, val reason: String) : KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_REJECT
}

/** Binary frame (§8.1): a [ChunkHeader] followed by [data]. */
public class TransferChunk(public val transferId: String, public val offset: Long, public val data: ByteArray) :
    KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_CHUNK

    override fun equals(other: Any?): Boolean = other is TransferChunk &&
        other.transferId == transferId && other.offset == offset && other.data.contentEquals(data)

    override fun hashCode(): Int = (transferId.hashCode() * 31 + offset.hashCode()) * 31 + data.contentHashCode()

    override fun toString(): String = "TransferChunk(transferId=$transferId, offset=$offset, data=${data.size} bytes)"
}

@Serializable
public data class TransferEnd(val transferId: String, val sha256: String) : KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_END
}

@Serializable
public data class TransferResult(val transferId: String, val ok: Boolean, val reason: String? = null) : KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_RESULT
}

@Serializable
public data class TransferCancel(val transferId: String) : KnownMessage {
    override val type: MessageType get() = MessageType.TRANSFER_CANCEL
}

/** The ERROR message (§14). Named to avoid clashing with [kotlin.Error]. */
@Serializable
public data class ErrorMessage(val code: String, val message: String) : KnownMessage {
    override val type: MessageType get() = MessageType.ERROR
}

/** A frame whose type this version doesn't define (§15.2). */
public class UnknownMessage(override val typeCode: Int, public val payload: ByteArray) : Message {
    override fun equals(other: Any?): Boolean =
        other is UnknownMessage && other.typeCode == typeCode && other.payload.contentEquals(payload)

    override fun hashCode(): Int = typeCode * 31 + payload.contentHashCode()

    override fun toString(): String = "UnknownMessage(typeCode=0x%02x, payload=%d bytes)".format(typeCode, payload.size)
}
