package io.github.akashgupta662642.clipdrop.protocol

import java.security.MessageDigest
import java.util.Base64

/** Identity derivation from PROTOCOL.md §3. */
public object DeviceIds {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** SHA-256 of the DER-encoded certificate. */
    public fun fingerprint(certificateDer: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificateDer)

    /** base64url (no padding) of the certificate's fingerprint: 43 characters. */
    public fun deviceId(certificateDer: ByteArray): String = fromFingerprint(fingerprint(certificateDer))

    public fun fromFingerprint(fingerprint: ByteArray): String {
        require(fingerprint.size == 32) { "A fingerprint is 32 bytes" }
        return encoder.encodeToString(fingerprint)
    }

    /** The 32 fingerprint bytes encoded by [deviceId], or null if it isn't a valid device ID. */
    public fun toFingerprint(deviceId: String): ByteArray? {
        if (!Validation.isDeviceId(deviceId)) return null
        val bytes = runCatching { decoder.decode(deviceId) }.getOrNull() ?: return null
        // Reject non-canonical encodings whose unused trailing bits are set.
        return bytes.takeIf { it.size == 32 && fromFingerprint(it) == deviceId }
    }

    /** Constant-time comparison of two fingerprints (or any equal-length secrets). */
    public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
