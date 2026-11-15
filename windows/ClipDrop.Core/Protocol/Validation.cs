using System.Text.RegularExpressions;

namespace ClipDrop.Core.Protocol;

/// <summary>Field-format checks shared by the codec and its callers (PROTOCOL.md §8.2).</summary>
public static partial class Validation
{
    public const long MaxNonce = uint.MaxValue;

    /// <summary>Lowercase <c>8-4-4-4-12</c> UUID text.</summary>
    public static bool IsUuid(string value) => UuidPattern().IsMatch(value);

    /// <summary>43 base64url characters: the encoding of a 32-byte fingerprint (§3).</summary>
    public static bool IsDeviceId(string value) => DeviceIdPattern().IsMatch(value);

    /// <summary>64 lowercase hex characters.</summary>
    public static bool IsSha256Hex(string value) => Sha256Pattern().IsMatch(value);

    /// <summary>22 base64url characters: the encoding of a 16-byte pairing token (§6).</summary>
    public static bool IsPairingToken(string value) => TokenPattern().IsMatch(value);

    /// <summary>True if <paramref name="value"/> contains a surrogate code unit without its partner.</summary>
    public static bool HasUnpairedSurrogate(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            if (char.IsHighSurrogate(c))
            {
                if (i + 1 >= value.Length || !char.IsLowSurrogate(value[i + 1]))
                {
                    return true;
                }

                i++;
            }
            else if (char.IsLowSurrogate(c))
            {
                return true;
            }
        }

        return false;
    }

    /// <summary>A device name after trimming (§2), or null if nothing remains.</summary>
    public static string? NormalizedDeviceName(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        string trimmed = value.Trim();
        return trimmed.Length == 0 ? null : trimmed;
    }

    [GeneratedRegex(@"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z", RegexOptions.CultureInvariant)]
    private static partial Regex UuidPattern();

    [GeneratedRegex(@"^[A-Za-z0-9_-]{43}\z", RegexOptions.CultureInvariant)]
    private static partial Regex DeviceIdPattern();

    [GeneratedRegex(@"^[0-9a-f]{64}\z", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Pattern();

    [GeneratedRegex(@"^[A-Za-z0-9_-]{22}\z", RegexOptions.CultureInvariant)]
    private static partial Regex TokenPattern();
}
