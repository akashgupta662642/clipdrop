package io.github.akashgupta662642.clipdrop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Binary layout of a TRANSFER_CHUNK payload (PROTOCOL.md §8.1):
 * a 16-byte transfer ID in network byte order, a big-endian u64 offset, then the data.
 */
public data class ChunkHeader(val transferId: String, val offset: Long) {
    init {
        require(Validation.isUuid(transferId)) { "transferId must be a lowercase UUID" }
        require(offset in 0..ProtocolConstants.MAX_JSON_INTEGER) { "offset out of range: $offset" }
    }

    /** Writes the 24 header bytes at the buffer's position. */
    public fun writeTo(buffer: ByteBuffer) {
        val uuid = UUID.fromString(transferId)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        buffer.putLong(offset)
    }

    public companion object {
        public const val SIZE: Int = ProtocolConstants.CHUNK_HEADER_SIZE

        /** Encodes a complete chunk payload: header followed by [data]. */
        public fun encodePayload(transferId: String, offset: Long, data: ByteArray): ByteArray {
            require(data.size in 1..ProtocolConstants.MAX_CHUNK_DATA) { "Chunk data must be 1..${ProtocolConstants.MAX_CHUNK_DATA} bytes" }
            val buffer = ByteBuffer.allocate(SIZE + data.size)
            ChunkHeader(transferId, offset).writeTo(buffer)
            buffer.put(data)
            return buffer.array()
        }

        /** Decodes a chunk payload, enforcing the decode-time rules of §8.1. */
        public fun decodePayload(payload: ByteArray): TransferChunk {
            if (payload.size < SIZE + 1 || payload.size > SIZE + ProtocolConstants.MAX_CHUNK_DATA) {
                throw ProtocolException(ErrorCode.PROTOCOL_ERROR, "TRANSFER_CHUNK payload of ${payload.size} bytes is outside ${SIZE + 1}..${SIZE + ProtocolConstants.MAX_CHUNK_DATA}")
            }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val transferId = UUID(buffer.long, buffer.long).toString()
            val offset = buffer.long
            if (offset !in 0..ProtocolConstants.MAX_JSON_INTEGER) {
                throw ProtocolException(ErrorCode.PROTOCOL_ERROR, "TRANSFER_CHUNK offset is outside 0..2^53-1")
            }
            return TransferChunk(transferId, offset, payload.copyOfRange(SIZE, payload.size))
        }
    }
}
