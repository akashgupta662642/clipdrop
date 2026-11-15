using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipDrop.Core.Protocol;

namespace ClipDrop.Core.Identity;

/// <summary>Encrypts secrets at rest. The Windows implementation is DPAPI; tests use a fake.</summary>
public interface ISecretProtector
{
    byte[] Protect(byte[] plaintext);

    byte[] Unprotect(byte[] protectedData);
}

/// <summary>DPAPI, scoped to the current Windows user.</summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiSecretProtector : ISecretProtector
{
    // Ties the blob to this purpose, so it can't be confused with other DPAPI data of the same user.
    private static readonly byte[] Entropy = "ClipDrop identity v1"u8.ToArray();

    public byte[] Protect(byte[] plaintext) => ProtectedData.Protect(plaintext, Entropy, DataProtectionScope.CurrentUser);

    public byte[] Unprotect(byte[] protectedData) => ProtectedData.Unprotect(protectedData, Entropy, DataProtectionScope.CurrentUser);
}

/// <summary>This device's identity (PROTOCOL.md §3): a certificate with its private key.</summary>
public sealed class HostIdentity : IDisposable
{
    internal HostIdentity(X509Certificate2 certificate)
    {
        Certificate = certificate;
        Fingerprint = DeviceIds.Fingerprint(certificate.RawData);
        DeviceId = DeviceIds.FromFingerprint(Fingerprint);
    }

    public X509Certificate2 Certificate { get; }

    public byte[] Fingerprint { get; }

    public string DeviceId { get; }

    public void Dispose() => Certificate.Dispose();
}

/// <summary>
/// Creates the identity on first run and loads it afterwards. The PKCS#12 bytes are encrypted with
/// <see cref="ISecretProtector"/> before they touch the disk.
/// </summary>
/// <remarks>
/// A certificate from <see cref="CertificateRequest.CreateSelfSigned"/> holds an ephemeral key that
/// SChannel can't use for <c>SslStream</c> server authentication on Windows. Exporting to PKCS#12 and
/// loading it back with <see cref="X509CertificateLoader.LoadPkcs12(byte[], string?, X509KeyStorageFlags, Pkcs12LoaderLimits?)"/>
/// gives a persisted key that it can.
/// </remarks>
public sealed class IdentityStore(string directory, ISecretProtector protector, TimeProvider timeProvider)
{
    public const string FileName = "identity.p12.protected";
    private static readonly TimeSpan Validity = TimeSpan.FromDays(25 * 365.25);

    public string FilePath => Path.Combine(directory, FileName);

    public HostIdentity LoadOrCreate(string deviceName)
    {
        if (File.Exists(FilePath))
        {
            return new HostIdentity(Load(protector.Unprotect(File.ReadAllBytes(FilePath))));
        }

        byte[] pkcs12 = Create(deviceName, timeProvider.GetUtcNow());
        try
        {
            Directory.CreateDirectory(directory);
            string temporary = FilePath + ".tmp";
            File.WriteAllBytes(temporary, protector.Protect(pkcs12));
            File.Move(temporary, FilePath, overwrite: true);
            return new HostIdentity(Load(pkcs12));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pkcs12);
        }
    }

    /// <summary>A fresh EC P-256 self-signed certificate, valid for 25 years, as PKCS#12 bytes.</summary>
    internal static byte[] Create(string deviceName, DateTimeOffset now)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var subject = new X500DistinguishedNameBuilder();
        subject.AddCommonName(Validation.NormalizedDeviceName(deviceName) ?? "ClipDrop");
        var request = new CertificateRequest(subject.Build(), key, HashAlgorithmName.SHA256);
        using X509Certificate2 certificate = request.CreateSelfSigned(now.AddDays(-1), now + Validity);
        return certificate.Export(X509ContentType.Pkcs12);
    }

    private static X509Certificate2 Load(byte[] pkcs12) =>
        X509CertificateLoader.LoadPkcs12(pkcs12, password: null, X509KeyStorageFlags.UserKeySet);
}
