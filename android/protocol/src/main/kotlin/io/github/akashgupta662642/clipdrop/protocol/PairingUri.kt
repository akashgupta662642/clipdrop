package io.github.akashgupta662642.clipdrop.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The pairing URI shown as a QR code by the host (PROTOCOL.md §6). */
public data class PairingUri(
    val hosts: List<String>,
    val port: Int,
    /** The host's fingerprint as base64url, which is also its device ID. */
    val fingerprint: String,
    val token: String,
    val name: String,
) {
    init {
        require(hosts.size in 1..MAX_HOSTS && hosts.all(::isIpv4)) { "hosts must be 1..$MAX_HOSTS IPv4 addresses" }
        require(port in 1..65535) { "port out of range" }
        require(Validation.isDeviceId(fingerprint)) { "fingerprint must be 43 base64url characters" }
        require(Validation.isPairingToken(token)) { "token must be 22 base64url characters" }
        require(Validation.normalizedDeviceName(name) == name) { "name must be non-empty and trimmed" }
    }

    public val version: Int get() = VERSION

    override fun toString(): String = "PairingUri(hosts=$hosts, port=$port, fingerprint=$fingerprint, token=<redacted>, name=$name)"

    /** Encodes this as a `clipdrop://pair?…` URI string. */
    public fun toUriString(): String = buildString {
        append(ProtocolConstants.URI_SCHEME).append("://").append(HOST)
        append("?v=").append(VERSION)
        append("&h=").append(hosts.joinToString(","))
        append("&p=").append(port)
        append("&fp=").append(fingerprint)
        append("&t=").append(token)
        append("&n=").append(percentEncode(name))
    }

    public sealed interface ParseResult {
        public data class Valid(val uri: PairingUri) : ParseResult
        public data object UnsupportedVersion : ParseResult
        public data class Invalid(val reason: String) : ParseResult
    }

    public companion object {
        public const val VERSION: Int = 1
        public const val MAX_HOSTS: Int = 16
        private const val HOST = "pair"
        private val KNOWN_KEYS = setOf("v", "h", "p", "fp", "t", "n")
        private val POSITIVE_INTEGER = Regex("^[1-9][0-9]{0,9}$")
        private val OCTET = Regex("^(0|[1-9][0-9]{0,2})$")

        public fun parse(text: String): ParseResult {
            val schemeEnd = text.indexOf("://")
            if (schemeEnd < 0 || !text.substring(0, schemeEnd).equals(ProtocolConstants.URI_SCHEME, ignoreCase = true)) {
                return ParseResult.Invalid("not a clipdrop:// URI")
            }
            val rest = text.substring(schemeEnd + 3)
            val queryStart = rest.indexOf('?')
            if (queryStart < 0) return ParseResult.Invalid("missing query")
            if (!rest.substring(0, queryStart).equals(HOST, ignoreCase = true)) return ParseResult.Invalid("not a pairing URI")

            val params = mutableMapOf<String, String>()
            for (part in rest.substring(queryStart + 1).split('&')) {
                val key = part.substringBefore('=')
                if (key !in KNOWN_KEYS) continue
                if (key in params) return ParseResult.Invalid("duplicate parameter $key")
                params[key] = percentDecode(part.substringAfter('=', "")) ?: return ParseResult.Invalid("bad encoding in $key")
            }

            val version = params["v"]?.let(::parsePositiveInt) ?: return ParseResult.Invalid("bad or missing v")
            if (version != VERSION) return ParseResult.UnsupportedVersion

            val hosts = params["h"]?.split(',') ?: return ParseResult.Invalid("missing h")
            if (hosts.size !in 1..MAX_HOSTS || !hosts.all(::isIpv4)) return ParseResult.Invalid("bad h")
            val port = params["p"]?.let(::parsePositiveInt)?.takeIf { it <= 65535 } ?: return ParseResult.Invalid("bad or missing p")
            val fingerprint = params["fp"]?.takeIf(Validation::isDeviceId) ?: return ParseResult.Invalid("bad or missing fp")
            val token = params["t"]?.takeIf(Validation::isPairingToken) ?: return ParseResult.Invalid("bad or missing t")
            val name = params["n"]?.let(Validation::normalizedDeviceName) ?: return ParseResult.Invalid("bad or missing n")
            if (Validation.hasUnpairedSurrogate(name)) return ParseResult.Invalid("bad n")

            return ParseResult.Valid(PairingUri(hosts, port, fingerprint, token, name))
        }

        private fun parsePositiveInt(value: String): Int? =
            if (POSITIVE_INTEGER.matches(value)) value.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt() else null

        private fun isIpv4(value: String): Boolean {
            val octets = value.split('.')
            return octets.size == 4 && octets.all { OCTET.matches(it) && it.toInt() <= 255 }
        }

        /** Strict RFC 3986 percent-decoding; `+` stays literal. Returns null on bad escapes or bad UTF-8. */
        private fun percentDecode(value: String): String? {
            val bytes = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val hi = Character.digit(value[i + 1], 16)
                    val lo = Character.digit(value[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                } else {
                    val encoded = c.toString().toByteArray(StandardCharsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    i++
                }
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        /** Percent-encodes everything except RFC 3986 unreserved characters. */
        private fun percentEncode(value: String): String = buildString {
            for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
                val b = byte.toInt() and 0xFF
                val c = b.toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                    append(c)
                } else {
                    append('%').append("0123456789ABCDEF"[b shr 4]).append("0123456789ABCDEF"[b and 0xF])
                }
            }
        }
    }
}
