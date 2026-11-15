using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace ClipDrop.Core.Protocol;

/// <summary>Result of <see cref="PairingUri.Parse"/>.</summary>
public abstract record PairingUriParseResult
{
    public sealed record Valid(PairingUri Uri) : PairingUriParseResult;

    public sealed record UnsupportedVersion : PairingUriParseResult;

    public sealed record Invalid(string Reason) : PairingUriParseResult;
}

/// <summary>The pairing URI shown as a QR code by the host (PROTOCOL.md §6).</summary>
public sealed partial class PairingUri
{
    public const int Version = 1;
    public const int MaxHosts = 16;
    private const string Host = "pair";
    private static readonly HashSet<string> KnownKeys = ["v", "h", "p", "fp", "t", "n"];
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    public PairingUri(IReadOnlyList<string> hosts, int port, string fingerprint, string token, string name)
    {
        ArgumentNullException.ThrowIfNull(hosts);
        if (hosts.Count is < 1 or > MaxHosts || !hosts.All(IsIpv4))
        {
            throw new ArgumentException($"hosts must be 1..{MaxHosts} IPv4 addresses.", nameof(hosts));
        }

        ArgumentOutOfRangeException.ThrowIfLessThan(port, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(port, 65535);
        if (!Validation.IsDeviceId(fingerprint))
        {
            throw new ArgumentException("fingerprint must be 43 base64url characters.", nameof(fingerprint));
        }

        if (!Validation.IsPairingToken(token))
        {
            throw new ArgumentException("token must be 22 base64url characters.", nameof(token));
        }

        if (Validation.NormalizedDeviceName(name) != name)
        {
            throw new ArgumentException("name must be non-empty and trimmed.", nameof(name));
        }

        Hosts = [.. hosts];
        Port = port;
        Fingerprint = fingerprint;
        Token = token;
        Name = name;
    }

    public IReadOnlyList<string> Hosts { get; }

    public int Port { get; }

    /// <summary>The host's fingerprint as base64url, which is also its device ID.</summary>
    public string Fingerprint { get; }

    public string Token { get; }

    public string Name { get; }

    public override string ToString() =>
        $"PairingUri(hosts={string.Join(',', Hosts)}, port={Port}, fingerprint={Fingerprint}, token=<redacted>, name={Name})";

    /// <summary>Encodes this as a <c>clipdrop://pair?…</c> URI string.</summary>
    public string ToUriString() => string.Create(
        CultureInfo.InvariantCulture,
        $"{ProtocolConstants.UriScheme}://{Host}?v={Version}&h={string.Join(',', Hosts)}&p={Port}&fp={Fingerprint}&t={Token}&n={PercentEncode(Name)}");

    public static PairingUriParseResult Parse(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        int schemeEnd = text.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd < 0 || !text[..schemeEnd].Equals(ProtocolConstants.UriScheme, StringComparison.OrdinalIgnoreCase))
        {
            return new PairingUriParseResult.Invalid("not a clipdrop:// URI");
        }

        string rest = text[(schemeEnd + 3)..];
        int queryStart = rest.IndexOf('?', StringComparison.Ordinal);
        if (queryStart < 0)
        {
            return new PairingUriParseResult.Invalid("missing query");
        }

        if (!rest[..queryStart].Equals(Host, StringComparison.OrdinalIgnoreCase))
        {
            return new PairingUriParseResult.Invalid("not a pairing URI");
        }

        var parameters = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string part in rest[(queryStart + 1)..].Split('&'))
        {
            int equals = part.IndexOf('=', StringComparison.Ordinal);
            string key = equals < 0 ? part : part[..equals];
            if (!KnownKeys.Contains(key))
            {
                continue;
            }

            if (parameters.ContainsKey(key))
            {
                return new PairingUriParseResult.Invalid($"duplicate parameter {key}");
            }

            string? value = PercentDecode(equals < 0 ? string.Empty : part[(equals + 1)..]);
            if (value is null)
            {
                return new PairingUriParseResult.Invalid($"bad encoding in {key}");
            }

            parameters[key] = value;
        }

        if (!parameters.TryGetValue("v", out string? v) || ParsePositiveInt(v) is not { } version)
        {
            return new PairingUriParseResult.Invalid("bad or missing v");
        }

        if (version != Version)
        {
            return new PairingUriParseResult.UnsupportedVersion();
        }

        if (!parameters.TryGetValue("h", out string? h) || h.Split(',') is not { Length: >= 1 and <= MaxHosts } hosts || !hosts.All(IsIpv4))
        {
            return new PairingUriParseResult.Invalid("bad or missing h");
        }

        if (!parameters.TryGetValue("p", out string? p) || ParsePositiveInt(p) is not (>= 1 and <= 65535 and var port))
        {
            return new PairingUriParseResult.Invalid("bad or missing p");
        }

        if (!parameters.TryGetValue("fp", out string? fingerprint) || !Validation.IsDeviceId(fingerprint))
        {
            return new PairingUriParseResult.Invalid("bad or missing fp");
        }

        if (!parameters.TryGetValue("t", out string? token) || !Validation.IsPairingToken(token))
        {
            return new PairingUriParseResult.Invalid("bad or missing t");
        }

        if (!parameters.TryGetValue("n", out string? n) || Validation.NormalizedDeviceName(n) is not { } name || Validation.HasUnpairedSurrogate(name))
        {
            return new PairingUriParseResult.Invalid("bad or missing n");
        }

        return new PairingUriParseResult.Valid(new PairingUri(hosts, port, fingerprint, token, name));
    }

    private static int? ParsePositiveInt(string value) =>
        PositiveIntegerPattern().IsMatch(value) && long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out long n) && n <= int.MaxValue
            ? (int)n
            : null;

    private static bool IsIpv4(string value)
    {
        string[] octets = value.Split('.');
        return octets.Length == 4 && octets.All(o => OctetPattern().IsMatch(o) && int.Parse(o, CultureInfo.InvariantCulture) <= 255);
    }

    /// <summary>Strict RFC 3986 percent-decoding; <c>+</c> stays literal. Returns null on bad escapes or bad UTF-8.</summary>
    private static string? PercentDecode(string value)
    {
        var bytes = new List<byte>(value.Length);
        Span<byte> utf8 = stackalloc byte[4];
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            if (c == '%')
            {
                if (i + 2 >= value.Length || !Uri.IsHexDigit(value[i + 1]) || !Uri.IsHexDigit(value[i + 2]))
                {
                    return null;
                }

                bytes.Add((byte)((Uri.FromHex(value[i + 1]) << 4) | Uri.FromHex(value[i + 2])));
                i += 2;
            }
            else
            {
                int written = Encoding.UTF8.GetBytes([c], utf8);
                for (int j = 0; j < written; j++)
                {
                    bytes.Add(utf8[j]);
                }
            }
        }

        try
        {
            return StrictUtf8.GetString([.. bytes]);
        }
        catch (DecoderFallbackException)
        {
            return null;
        }
    }

    /// <summary>Percent-encodes everything except RFC 3986 unreserved characters.</summary>
    private static string PercentEncode(string value)
    {
        var builder = new StringBuilder();
        foreach (byte b in Encoding.UTF8.GetBytes(value))
        {
            char c = (char)b;
            if (char.IsAsciiLetterOrDigit(c) || c is '-' or '.' or '_' or '~')
            {
                builder.Append(c);
            }
            else
            {
                builder.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
            }
        }

        return builder.ToString();
    }

    [GeneratedRegex(@"^[1-9][0-9]{0,9}\z", RegexOptions.CultureInvariant)]
    private static partial Regex PositiveIntegerPattern();

    [GeneratedRegex(@"^(0|[1-9][0-9]{0,2})\z", RegexOptions.CultureInvariant)]
    private static partial Regex OctetPattern();
}
