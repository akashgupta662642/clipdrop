package io.github.akashgupta662642.clipdrop.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Converts between [Message]s and [Frame]s.
 *
 * Decoding is lenient where PROTOCOL.md §8.2 says receivers must be (field order, whitespace,
 * unknown fields, `null` optionals, escapes) and strict everywhere else: every decode-time rule of
 * §8.2 raises [ProtocolException] with [ErrorCode.PROTOCOL_ERROR]. Encoding produces the canonical
 * form, which the golden vectors pin byte for byte.
 */
public object MessageCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = false
        allowTrailingComma = false
        allowComments = false
        allowSpecialFloatingPointValues = false
    }

    private val INTEGER_LITERAL = Regex("^-?(0|[1-9][0-9]*)$")

    /**
     * Encodes [message] canonically.
     *
     * @throws IllegalArgumentException if the message breaks a §8.2 rule or doesn't fit in one
     *   frame. Check [fitsInFrame] first for content of unbounded size, such as clipboard text.
     */
    public fun encode(message: Message): Frame {
        validate(message)?.let { throw IllegalArgumentException("Invalid ${message.javaClass.simpleName}: $it") }
        return Frame(message.typeCode, payloadBytes(message))
    }

    /** True if [message] encodes to a frame within the §7 limit. */
    public fun fitsInFrame(message: Message): Boolean = 1L + payloadBytes(message).size <= ProtocolConstants.MAX_FRAME_LENGTH

    /** @throws ProtocolException with [ErrorCode.PROTOCOL_ERROR] if the payload breaks §8.2. */
    public fun decode(frame: Frame): Message {
        val message = when (frame.type) {
            null -> UnknownMessage(frame.typeCode, frame.payload)
            MessageType.HELLO -> decodeJson(frame, Hello.serializer())
            MessageType.HELLO_ACK -> decodeJson(frame, HelloAck.serializer())
            MessageType.PAIR_REQUEST -> decodeJson(frame, PairRequest.serializer())
            MessageType.PAIR_RESULT -> decodeJson(frame, PairResult.serializer())
            MessageType.UNPAIR -> decodeJson(frame, Unpair.serializer())
            MessageType.PING -> decodeJson(frame, Ping.serializer())
            MessageType.PONG -> decodeJson(frame, Pong.serializer())
            MessageType.CLIP_TEXT -> decodeJson(frame, ClipText.serializer())
            MessageType.TRANSFER_OFFER -> decodeJson(frame, TransferOffer.serializer())
            MessageType.TRANSFER_ACCEPT -> decodeJson(frame, TransferAccept.serializer())
            MessageType.TRANSFER_REJECT -> decodeJson(frame, TransferReject.serializer())
            MessageType.TRANSFER_CHUNK -> ChunkHeader.decodePayload(frame.payload)
            MessageType.TRANSFER_END -> decodeJson(frame, TransferEnd.serializer())
            MessageType.TRANSFER_RESULT -> decodeJson(frame, TransferResult.serializer())
            MessageType.TRANSFER_CANCEL -> decodeJson(frame, TransferCancel.serializer())
            MessageType.ERROR -> decodeJson(frame, ErrorMessage.serializer())
        }
        validate(message)?.let { throw protocolError("${frame.type}: $it") }
        return message
    }

    @Suppress("UNCHECKED_CAST")
    private fun payloadBytes(message: Message): ByteArray = when (message) {
        is TransferChunk -> ChunkHeader.encodePayload(message.transferId, message.offset, message.data)
        is UnknownMessage -> message.payload
        else -> json.encodeToString(serializerFor(message) as KSerializer<Message>, message).encodeToByteArray()
    }

    private fun serializerFor(message: Message): KSerializer<*> = when (message) {
        is Hello -> Hello.serializer()
        is HelloAck -> HelloAck.serializer()
        is PairRequest -> PairRequest.serializer()
        is PairResult -> PairResult.serializer()
        is Unpair -> Unpair.serializer()
        is Ping -> Ping.serializer()
        is Pong -> Pong.serializer()
        is ClipText -> ClipText.serializer()
        is TransferOffer -> TransferOffer.serializer()
        is TransferAccept -> TransferAccept.serializer()
        is TransferReject -> TransferReject.serializer()
        is TransferEnd -> TransferEnd.serializer()
        is TransferResult -> TransferResult.serializer()
        is TransferCancel -> TransferCancel.serializer()
        is ErrorMessage -> ErrorMessage.serializer()
        is TransferChunk, is UnknownMessage -> error("Not a JSON message: $message")
    }

    private fun <T> decodeJson(frame: Frame, serializer: KSerializer<T>): T {
        val text = decodeUtf8Strict(frame.payload)
        val element = if (text.isEmpty()) JsonObject(emptyMap()) else parse(text)
        if (element !is JsonObject) throw protocolError("${frame.type} payload is not a JSON object")
        checkShape(element, serializer.descriptor, frame.type.toString())
        return try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            throw protocolError("${frame.type} payload doesn't match the message schema", e)
        } catch (e: IllegalArgumentException) {
            throw protocolError("${frame.type} payload doesn't match the message schema", e)
        }
    }

    private fun parse(text: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw protocolError("Payload is not valid JSON", e)
    }

    /**
     * Checks every field the message defines against its declared kind before kotlinx decodes it.
     * kotlinx alone would accept some inputs the spec forbids, such as `"1"` for an integer.
     */
    private fun checkShape(obj: JsonObject, descriptor: SerialDescriptor, typeName: String) {
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val field = descriptor.getElementDescriptor(i)
            val value = obj[name]
            if (value == null || value is JsonNull) {
                if (value == null && descriptor.isElementOptional(i)) continue
                if (value is JsonNull && field.isNullable) continue
                throw protocolError("$typeName.$name is missing")
            }
            if (value !is JsonPrimitive) throw protocolError("$typeName.$name has the wrong JSON type")
            when (field.kind) {
                PrimitiveKind.STRING -> {
                    if (!value.isString) throw protocolError("$typeName.$name must be a string")
                    if (Validation.hasUnpairedSurrogate(value.content)) {
                        throw protocolError("$typeName.$name contains an unpaired surrogate")
                    }
                }
                PrimitiveKind.INT, PrimitiveKind.LONG -> {
                    if (value.isString || !INTEGER_LITERAL.matches(value.content)) {
                        throw protocolError("$typeName.$name must be an integer")
                    }
                }
                PrimitiveKind.BOOLEAN -> {
                    if (value.isString || (value.content != "true" && value.content != "false")) {
                        throw protocolError("$typeName.$name must be a boolean")
                    }
                }
                else -> throw protocolError("$typeName.$name has an unsupported kind")
            }
        }
    }

    /** Returns a description of the first broken rule, or null if [message] is valid. */
    internal fun validate(message: Message): String? = when (message) {
        is Hello -> checkHello(message.protocolVersion, message.deviceId, message.deviceName, message.platform, message.appVersion)
        is HelloAck -> checkHello(message.protocolVersion, message.deviceId, message.deviceName, message.platform, message.appVersion)
        is PairRequest -> checkStrings(message.token)
        is PairResult -> checkReason(message.ok, message.reason)
        is Unpair, is UnknownMessage -> null
        is Ping -> if (message.nonce in 0..Validation.MAX_NONCE) null else "nonce out of range"
        is Pong -> if (message.nonce in 0..Validation.MAX_NONCE) null else "nonce out of range"
        is ClipText -> when {
            !Validation.isUuid(message.clipId) -> "clipId is not a lowercase UUID"
            message.text.isEmpty() -> "text is empty"
            !inJsonRange(message.timestamp) -> "timestamp out of range"
            else -> checkStrings(message.text)
        }
        is TransferOffer -> when {
            !Validation.isUuid(message.transferId) -> "transferId is not a lowercase UUID"
            !inJsonRange(message.size) -> "size out of range"
            else -> checkStrings(message.purpose, message.name, message.relativePath, message.mime)
        }
        is TransferAccept -> checkTransferId(message.transferId) ?: if (inJsonRange(message.offset)) null else "offset out of range"
        is TransferReject -> checkTransferId(message.transferId) ?: checkStrings(message.reason)
        is TransferChunk -> checkTransferId(message.transferId)
            ?: if (inJsonRange(message.offset)) null else "offset out of range"
        is TransferEnd -> checkTransferId(message.transferId)
            ?: if (Validation.isSha256Hex(message.sha256)) null else "sha256 is not 64 lowercase hex characters"
        is TransferResult -> checkTransferId(message.transferId) ?: checkReason(message.ok, message.reason)
        is TransferCancel -> checkTransferId(message.transferId)
        is ErrorMessage -> checkStrings(message.code, message.message)
    }

    private fun checkHello(version: Int, deviceId: String, name: String, platform: String, appVersion: String): String? = when {
        version < 1 -> "protocolVersion must be at least 1"
        !Validation.isDeviceId(deviceId) -> "deviceId is not 43 base64url characters"
        Validation.normalizedDeviceName(name) == null -> "deviceName is empty"
        else -> checkStrings(name, platform, appVersion)
    }

    private fun checkReason(ok: Boolean, reason: String?): String? = when {
        ok && reason != null -> "reason must be absent when ok is true"
        !ok && reason == null -> "reason is required when ok is false"
        reason != null -> checkStrings(reason)
        else -> null
    }

    private fun checkTransferId(id: String): String? = if (Validation.isUuid(id)) null else "transferId is not a lowercase UUID"

    private fun checkStrings(vararg values: String): String? =
        if (values.any(Validation::hasUnpairedSurrogate)) "a string contains an unpaired surrogate" else null

    private fun inJsonRange(value: Long): Boolean = value in 0..ProtocolConstants.MAX_JSON_INTEGER

    private fun decodeUtf8Strict(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw protocolError("Payload is not valid UTF-8", e)
    }

    private fun protocolError(message: String, cause: Throwable? = null) =
        ProtocolException(ErrorCode.PROTOCOL_ERROR, message, cause)
}
