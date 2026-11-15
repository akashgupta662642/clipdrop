package io.github.akashgupta662642.clipdrop.protocol

/** How a frame's payload is encoded (PROTOCOL.md §7). */
public enum class PayloadKind { JSON, BINARY }

/** Every frame type in PROTOCOL.md §8.1, with its wire code. */
public enum class MessageType(
    public val code: Int,
    public val payloadKind: PayloadKind,
    /** True for the frames allowed before pairing completes (§9.1). */
    public val allowedBeforePairing: Boolean,
) {
    HELLO(0x01, PayloadKind.JSON, allowedBeforePairing = true),
    HELLO_ACK(0x02, PayloadKind.JSON, allowedBeforePairing = true),
    PAIR_REQUEST(0x03, PayloadKind.JSON, allowedBeforePairing = true),
    PAIR_RESULT(0x04, PayloadKind.JSON, allowedBeforePairing = true),
    UNPAIR(0x05, PayloadKind.JSON, allowedBeforePairing = false),
    PING(0x10, PayloadKind.JSON, allowedBeforePairing = false),
    PONG(0x11, PayloadKind.JSON, allowedBeforePairing = false),
    CLIP_TEXT(0x20, PayloadKind.JSON, allowedBeforePairing = false),
    TRANSFER_OFFER(0x30, PayloadKind.JSON, allowedBeforePairing = false),
    TRANSFER_ACCEPT(0x31, PayloadKind.JSON, allowedBeforePairing = false),
    TRANSFER_REJECT(0x32, PayloadKind.JSON, allowedBeforePairing = false),
    TRANSFER_CHUNK(0x33, PayloadKind.BINARY, allowedBeforePairing = false),
    TRANSFER_END(0x34, PayloadKind.JSON, allowedBeforePairing = false),
    TRANSFER_RESULT(0x35, PayloadKind.JSON, allowedBeforePairing = false),
    TRANSFER_CANCEL(0x36, PayloadKind.JSON, allowedBeforePairing = false),
    ERROR(0x7F, PayloadKind.JSON, allowedBeforePairing = true),
    ;

    public companion object {
        private val byCode: Map<Int, MessageType> = entries.associateBy { it.code }

        /** Returns the type for [code], or null if this protocol version doesn't define it (§15.2). */
        public fun fromCode(code: Int): MessageType? = byCode[code]
    }
}
