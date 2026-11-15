using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using ClipDrop.Core.Identity;
using ClipDrop.Core.Protocol;
using ClipDrop.Core.Storage;

namespace ClipDrop.Core.Tests.Net;

internal sealed class InMemoryPairedDeviceStore : IPairedDeviceStore
{
    private readonly ConcurrentDictionary<string, PairedDevice> _devices = new(StringComparer.Ordinal);

    public PairedDevice? Find(string deviceId) => _devices.GetValueOrDefault(deviceId);

    public IReadOnlyList<PairedDevice> All() => [.. _devices.Values];

    public void Upsert(PairedDevice device) => _devices[device.DeviceId] = device;

    public bool Remove(string deviceId) => _devices.TryRemove(deviceId, out _);
}

internal static class TestIdentities
{
    public static X509Certificate2 Certificate(string name) =>
        X509CertificateLoader.LoadPkcs12(IdentityStore.Create(name, DateTimeOffset.UtcNow), null);

    public static HostIdentity Host(string name = "Test PC") => new(Certificate(name));
}

/// <summary>Plays the phone against a real <c>HostServer</c> over loopback TLS.</summary>
internal sealed class TestPhone : IAsyncDisposable
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);
    private readonly bool _ownsCertificate;
    private TcpClient? _tcp;

    public TestPhone(string name = "Test Phone")
        : this(TestIdentities.Certificate(name), ownsCertificate: true)
    {
    }

    /// <summary>A second connection for the same device: same certificate, same identity.</summary>
    public TestPhone(X509Certificate2 certificate, bool ownsCertificate = false)
    {
        Certificate = certificate;
        DeviceId = DeviceIds.FromCertificate(certificate.RawData);
        _ownsCertificate = ownsCertificate;
    }

    public X509Certificate2 Certificate { get; }

    public string DeviceId { get; }

    public SslStream Stream { get; private set; } = null!;

    public Hello Hello(string? deviceId = null) => new(1, deviceId ?? DeviceId, "Test Phone", "android", "0.1.0");

    /// <summary>TLS-connects to 127.0.0.1:<paramref name="port"/>, pinning the host like the Android app does.</summary>
    public async Task ConnectAsync(int port, byte[] hostFingerprint, bool presentCertificate = true)
    {
        _tcp = new TcpClient();
        await _tcp.ConnectAsync(IPAddress.Loopback, port).WaitAsync(Timeout);
        Stream = new SslStream(_tcp.GetStream(), leaveInnerStreamOpen: false);
        await Stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = string.Empty,
            ClientCertificates = presentCertificate ? [Certificate] : null,
            LocalCertificateSelectionCallback = presentCertificate ? (_, _, _, _, _) => Certificate : null,
            RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                certificate is not null && DeviceIds.FixedTimeEquals(DeviceIds.Fingerprint(certificate.GetRawCertData()), hostFingerprint),
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
        }).WaitAsync(Timeout);
    }

    public async Task SendAsync(ProtocolMessage message)
    {
        await FrameCodec.WriteFrameAsync(Stream, MessageCodec.Encode(message)).AsTask().WaitAsync(Timeout);
        await Stream.FlushAsync().WaitAsync(Timeout);
    }

    public async Task<ProtocolMessage> ReceiveAsync() =>
        MessageCodec.Decode(await FrameCodec.ReadFrameAsync(Stream).AsTask().WaitAsync(Timeout));

    /// <summary>True if the host refuses this phone at any point up to and including HELLO_ACK.</summary>
    public async Task<bool> IsRefusedAsync(int port, byte[] hostFingerprint, bool presentCertificate = true)
    {
        try
        {
            await ConnectAsync(port, hostFingerprint, presentCertificate);
            await SendAsync(Hello());
            await ReceiveAsync();
            return false;
        }
        catch (Exception e) when (e is AuthenticationException or IOException)
        {
            return true;
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (Stream is not null)
        {
            await Stream.DisposeAsync();
        }

        _tcp?.Dispose();
        if (_ownsCertificate)
        {
            Certificate.Dispose();
        }
    }
}

/// <summary>Two connected loopback TCP streams.</summary>
internal sealed class SocketPair : IDisposable
{
    private readonly TcpClient _client;
    private readonly TcpClient _server;

    private SocketPair(TcpClient client, TcpClient server)
    {
        _client = client;
        _server = server;
        A = client.GetStream();
        B = server.GetStream();
    }

    public NetworkStream A { get; }

    public NetworkStream B { get; }

    public static async Task<SocketPair> CreateAsync()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var client = new TcpClient();
            Task connect = client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port);
            TcpClient server = await listener.AcceptTcpClientAsync();
            await connect;
            return new SocketPair(client, server);
        }
        finally
        {
            listener.Stop();
        }
    }

    public void Dispose()
    {
        _client.Dispose();
        _server.Dispose();
    }
}

/// <summary>Wraps a stream; writes wait while <see cref="Gate"/> is held, so tests can observe queueing.</summary>
internal sealed class GatedStream(Stream inner) : Stream
{
    public SemaphoreSlim Gate { get; } = new(1, 1);

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => true;

    public override long Length => throw new NotSupportedException();

    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
        inner.ReadAsync(buffer, cancellationToken);

    private int _waitingWriters;

    /// <summary>Writes currently held at the gate.</summary>
    public int WaitingWriters => Volatile.Read(ref _waitingWriters);

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        Interlocked.Increment(ref _waitingWriters);
        try
        {
            await Gate.WaitAsync(cancellationToken);
        }
        finally
        {
            Interlocked.Decrement(ref _waitingWriters);
        }

        try
        {
            await inner.WriteAsync(buffer, cancellationToken);
        }
        finally
        {
            Gate.Release();
        }
    }

    public override Task FlushAsync(CancellationToken cancellationToken) => inner.FlushAsync(cancellationToken);

    public override void Flush() => inner.Flush();

    public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);

    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            inner.Dispose();
            Gate.Dispose();
        }

        base.Dispose(disposing);
    }
}
