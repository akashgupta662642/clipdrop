package io.github.akashgupta662642.clipdrop.protocol

/** Field-format checks shared by the codec and its callers (PROTOCOL.md §8.2). */
public object Validation {
    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{22}$")

    public const val MAX_NONCE: Long = 0xFFFF_FFFFL

    /** Lowercase `8-4-4-4-12` UUID text. */
    public fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    /** 43 base64url characters: the encoding of a 32-byte fingerprint (§3). */
    public fun isDeviceId(value: String): Boolean = DEVICE_ID_PATTERN.matches(value)

    /** 64 lowercase hex characters. */
    public fun isSha256Hex(value: String): Boolean = SHA256_PATTERN.matches(value)

    /** 22 base64url characters: the encoding of a 16-byte pairing token (§6). */
    public fun isPairingToken(value: String): Boolean = TOKEN_PATTERN.matches(value)

    /** True if [value] contains a surrogate code unit without its partner. */
    public fun hasUnpairedSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return true
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return true
            i++
        }
        return false
    }

    /** A device name after trimming (§2), or null if nothing remains. */
    public fun normalizedDeviceName(value: String): String? = value.trim().takeIf { it.isNotEmpty() }
}
