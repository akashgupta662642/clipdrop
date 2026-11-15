using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipDrop.Core.Identity;

namespace ClipDrop.Core.Tests.Identity;

public sealed class IdentityStoreTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "clipdrop-tests", Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    [Fact]
    public void Creates_an_EC_P256_self_signed_certificate_valid_for_25_years()
    {
        using HostIdentity identity = new IdentityStore(_directory, new XorProtector(), TimeProvider.System).LoadOrCreate("Office PC");
        X509Certificate2 certificate = identity.Certificate;

        Assert.True(certificate.HasPrivateKey);
        Assert.Equal("CN=Office PC", certificate.Subject);
        Assert.Equal(certificate.Subject, certificate.Issuer);
        Assert.Equal("1.2.840.10045.4.3.2", certificate.SignatureAlgorithm.Value); // ecdsa-with-SHA256
        using ECDsa? key = certificate.GetECDsaPublicKey();
        Assert.Equal("1.2.840.10045.3.1.7", key!.ExportParameters(false).Curve.Oid.Value); // P-256
        Assert.InRange((certificate.NotAfter - certificate.NotBefore).TotalDays, 25 * 365, 25 * 366 + 2);
        Assert.Equal(43, identity.DeviceId.Length);
    }

    [Fact]
    public void Loading_again_returns_the_same_identity()
    {
        var store = new IdentityStore(_directory, new XorProtector(), TimeProvider.System);
        string first;
        using (HostIdentity created = store.LoadOrCreate("PC"))
        {
            first = created.DeviceId;
        }

        using HostIdentity loaded = store.LoadOrCreate("Renamed PC");
        Assert.Equal(first, loaded.DeviceId);
        Assert.True(loaded.Certificate.HasPrivateKey);
    }

    [Fact]
    public void The_file_on_disk_goes_through_the_protector()
    {
        var protector = new XorProtector();
        var store = new IdentityStore(_directory, protector, TimeProvider.System);
        using HostIdentity identity = store.LoadOrCreate("PC");

        Assert.Equal(1, protector.ProtectCalls);
        byte[] onDisk = File.ReadAllBytes(store.FilePath);
        Assert.ThrowsAny<CryptographicException>(() => X509CertificateLoader.LoadPkcs12(onDisk, null));
    }

    [Fact]
    public void Dpapi_round_trips_for_the_current_user()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "DPAPI is Windows-only.");
#pragma warning disable CA1416 // Guarded by the skip above.
        var dpapi = new DpapiSecretProtector();
        byte[] secret = RandomNumberGenerator.GetBytes(64);
        byte[] sealedBytes = dpapi.Protect(secret);
        Assert.NotEqual(secret, sealedBytes);
        Assert.Equal(secret, dpapi.Unprotect(sealedBytes));
#pragma warning restore CA1416
    }

    /// <summary>A reversible stand-in for DPAPI that makes the file unreadable as plain PKCS#12.</summary>
    private sealed class XorProtector : ISecretProtector
    {
        public int ProtectCalls { get; private set; }

        public byte[] Protect(byte[] plaintext)
        {
            ProtectCalls++;
            return Xor(plaintext);
        }

        public byte[] Unprotect(byte[] protectedData) => Xor(protectedData);

        private static byte[] Xor(byte[] data) => data.Select(b => (byte)(b ^ 0x5A)).ToArray();
    }
}
