package io.github.akashgupta662642.clipdrop.protocol

/**
 * One frame on the wire (PROTOCOL.md §7): a type code and its payload. [typeCode] may be a
 * code this version doesn't define; such frames are still valid frames (§15.2).
 */
public class Frame(public val typeCode: Int, public val payload: ByteArray) {
    init {
        require(typeCode in 0..0xFF) { "Type code must fit in one byte: $typeCode" }
        require(payload.size <= ProtocolConstants.MAX_FRAME_LENGTH - 1) {
            "Payload of ${payload.size} bytes exceeds the frame limit"
        }
    }

    /** The message type, or null if this protocol version doesn't define [typeCode]. */
    public val type: MessageType? get() = MessageType.fromCode(typeCode)

    /** Value of the frame's length field: the type byte plus the payload. */
    public val length: Int get() = 1 + payload.size

    override fun equals(other: Any?): Boolean =
        other is Frame && other.typeCode == typeCode && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * typeCode + payload.contentHashCode()

    override fun toString(): String =
        "Frame(type=${type ?: "0x%02x".format(typeCode)}, payload=${payload.size} bytes)"
}
