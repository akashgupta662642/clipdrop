using System.Buffers.Text;
using System.Security.Cryptography;

namespace ClipDrop.Core.Protocol;

/// <summary>Identity derivation from PROTOCOL.md §3.</summary>
public static class DeviceIds
{
    public const int FingerprintSize = 32;

    /// <summary>SHA-256 of the DER-encoded certificate.</summary>
    public static byte[] Fingerprint(ReadOnlySpan<byte> certificateDer) => SHA256.HashData(certificateDer);

    /// <summary>base64url (no padding) of the certificate's fingerprint: 43 characters.</summary>
    public static string FromCertificate(ReadOnlySpan<byte> certificateDer) => FromFingerprint(Fingerprint(certificateDer));

    public static string FromFingerprint(ReadOnlySpan<byte> fingerprint)
    {
        if (fingerprint.Length != FingerprintSize)
        {
            throw new ArgumentException("A fingerprint is 32 bytes.", nameof(fingerprint));
        }

        return Base64Url.EncodeToString(fingerprint);
    }

    /// <summary>The 32 fingerprint bytes encoded by <paramref name="deviceId"/>, or null if it isn't a valid device ID.</summary>
    public static byte[]? ToFingerprint(string deviceId)
    {
        ArgumentNullException.ThrowIfNull(deviceId);
        if (!Validation.IsDeviceId(deviceId))
        {
            return null;
        }

        try
        {
            byte[] bytes = Base64Url.DecodeFromChars(deviceId);
            // Reject non-canonical encodings whose unused trailing bits are set.
            return bytes.Length == FingerprintSize && FromFingerprint(bytes) == deviceId ? bytes : null;
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>Constant-time comparison, so a pin check doesn't leak how many bytes matched.</summary>
    public static bool FixedTimeEquals(ReadOnlySpan<byte> a, ReadOnlySpan<byte> b) =>
        CryptographicOperations.FixedTimeEquals(a, b);
}
