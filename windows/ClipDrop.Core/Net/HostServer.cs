using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipDrop.Core.Identity;
using ClipDrop.Core.Pairing;
using ClipDrop.Core.Protocol;
using ClipDrop.Core.Storage;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipDrop.Core.Net;

/// <summary>A paired device with a live session.</summary>
public sealed record HostSessionInfo(PairedDevice Device, IPEndPoint RemoteEndPoint, PeerSession Session);

/// <summary>
/// The PC's TCP server (PROTOCOL.md §4): TLS with required client certificates, pinned to paired
/// devices (or open to new ones while pairing mode is), then the host side of HELLO and pairing.
/// Each authenticated device gets one <see cref="PeerSession"/>; a newer connection replaces an older one.
/// </summary>
public sealed partial class HostServer : IAsyncDisposable
{
    private static readonly HashSet<TlsCipherSuite> AllowedCipherSuites =
    [
        TlsCipherSuite.TLS_AES_128_GCM_SHA256,
        TlsCipherSuite.TLS_AES_256_GCM_SHA384,
        TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
    ];

    private const string P256Oid = "1.2.840.10045.3.1.7";

    private readonly HostIdentity _identity;
    private readonly IPairedDeviceStore _devices;
    private readonly PairingCoordinator _pairing;
    private readonly Func<string> _deviceName;
    private readonly string _appVersion;
    private readonly TimeProvider _time;
    private readonly ILoggerFactory _loggers;
    private readonly ILogger _logger;
    private readonly SslStreamCertificateContext _certificateContext;
    private readonly ConcurrentDictionary<string, HostSessionInfo> _sessions = new(StringComparer.Ordinal);
    private readonly CancellationTokenSource _stopping = new();
    private TcpListener? _listener;

    public HostServer(
        HostIdentity identity,
        IPairedDeviceStore devices,
        PairingCoordinator pairing,
        Func<string> deviceName,
        string appVersion,
        TimeProvider timeProvider,
        ILoggerFactory? loggers = null)
    {
        ArgumentNullException.ThrowIfNull(identity);
        _identity = identity;
        _devices = devices;
        _pairing = pairing;
        _deviceName = deviceName;
        _appVersion = appVersion;
        _time = timeProvider;
        _loggers = loggers ?? NullLoggerFactory.Instance;
        _logger = _loggers.CreateLogger<HostServer>();
        _certificateContext = SslStreamCertificateContext.Create(identity.Certificate, additionalCertificates: null, offline: true);
    }

    /// <summary>The port actually bound (§4.1).</summary>
    public int Port { get; private set; }

    public IReadOnlyCollection<HostSessionInfo> Sessions => [.. _sessions.Values];

    public event EventHandler<HostSessionInfo>? SessionStarted;

    public event EventHandler<HostSessionInfo>? SessionEnded;

    public event EventHandler<PairedDevice>? DevicePaired;

    /// <summary>
    /// Binds the first free port among <paramref name="candidatePorts"/> (§4.1: the default port, then the
    /// last-used one), falling back to an ephemeral port, and starts accepting connections.
    /// </summary>
    /// <param name="candidatePorts">Ports to try in order before an ephemeral one.</param>
    /// <param name="bindAddress">
    /// <see cref="IPAddress.Any"/> (the default) for real use. Tests, the interop harness and emulator
    /// development use <see cref="IPAddress.Loopback"/>, which needs no firewall exception.
    /// </param>
    public void Start(IEnumerable<int> candidatePorts, IPAddress? bindAddress = null)
    {
        ArgumentNullException.ThrowIfNull(candidatePorts);
        foreach (int port in candidatePorts.Append(0))
        {
            var listener = new TcpListener(bindAddress ?? IPAddress.Any, port) { ExclusiveAddressUse = true };
            try
            {
                listener.Start();
            }
            catch (SocketException)
            {
                continue;
            }

            _listener = listener;
            Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            LogListening(Port);
            _ = Task.Run(AcceptLoopAsync);
            return;
        }
    }

    /// <summary>Unpairs a device (§10.4): forgets its pin and, if it is connected, tells it before closing.</summary>
    public async Task UnpairAsync(string deviceId)
    {
        _devices.Remove(deviceId);
        if (_sessions.TryRemove(deviceId, out HostSessionInfo? live))
        {
            await live.Session.UnpairAsync().ConfigureAwait(false);
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _stopping.CancelAsync().ConfigureAwait(false);
        _listener?.Stop();
        foreach (HostSessionInfo info in _sessions.Values)
        {
            await info.Session.DisposeAsync().ConfigureAwait(false);
        }

        _stopping.Dispose();
    }

    private async Task AcceptLoopAsync()
    {
        while (!_stopping.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener!.AcceptTcpClientAsync(_stopping.Token).ConfigureAwait(false);
            }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException)
            {
                return;
            }

            _ = Task.Run(() => HandleClientAsync(client));
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        client.NoDelay = true;
        var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
        var ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
        bool handedOver = false;
        bool holdsPairingSlot = false;
        try
        {
            using (var handshake = new CancellationTokenSource(ProtocolConstants.HandshakeTimeout, _time))
            using (var linked = CancellationTokenSource.CreateLinkedTokenSource(handshake.Token, _stopping.Token))
            {
                await ssl.AuthenticateAsServerAsync(ServerOptions(), linked.Token).ConfigureAwait(false);
            }

            // §4.2/§4.3: check what was negotiated, and the pin again, after every handshake.
            if (!NegotiatedWithinPolicy(ssl) || ssl.RemoteCertificate is null)
            {
                LogRejected(remote, "outside the TLS policy");
                return;
            }

            byte[] fingerprint = DeviceIds.Fingerprint(ssl.RemoteCertificate.GetRawCertData());
            string clientId = DeviceIds.FromFingerprint(fingerprint);
            PairedDevice? device = _devices.Find(clientId);
            if (device is null && !_pairing.AcceptsUnknownClients)
            {
                LogRejected(remote, "not paired");
                return;
            }

            Hello hello = await ReceiveAsync<Hello>(ssl, ProtocolConstants.HandshakeTimeout).ConfigureAwait(false);
            if (hello.DeviceId != clientId)
            {
                await SendErrorAsync(ssl, ErrorCode.ProtocolError, "HELLO deviceId doesn't match the client certificate").ConfigureAwait(false);
                return;
            }

            // §15.1: we only speak version 1, so the negotiated version is 1 whenever the client's maximum is at least 1.
            int version = Math.Min(hello.ProtocolVersion, ProtocolConstants.ProtocolVersion);
            string clientName = Validation.NormalizedDeviceName(hello.DeviceName) ?? "Unnamed device";
            await WriteAsync(ssl, new HelloAck(version, _identity.DeviceId, _deviceName(), "windows", _appVersion, device is not null)).ConfigureAwait(false);

            if (device is null)
            {
                holdsPairingSlot = _pairing.TryBeginUnpairedSession();
                if (!holdsPairingSlot)
                {
                    LogRejected(remote, "too many unpaired connections");
                    return;
                }

                PairRequest request = await ReceiveAsync<PairRequest>(ssl, ProtocolConstants.PairingSessionTimeout).ConfigureAwait(false);
                PairingAttempt attempt = _pairing.TryPair(request.Token);
                if (attempt != PairingAttempt.Success)
                {
                    LogPairingFailed(remote, attempt);
                    await WriteAsync(ssl, new PairResult(false, attempt.ToWireReason())).ConfigureAwait(false);
                    return;
                }

                device = new PairedDevice(clientId, clientName, hello.Platform, _time.GetUtcNow(), _time.GetUtcNow());
                _devices.Upsert(device);
                await WriteAsync(ssl, new PairResult(true)).ConfigureAwait(false);
                LogPaired(clientId, clientName);
                DevicePaired?.Invoke(this, device);
            }
            else
            {
                device = device with { Name = clientName, Platform = hello.Platform, LastSeenAt = _time.GetUtcNow() };
                _devices.Upsert(device);
            }

            StartSession(ssl, device, remote);
            handedOver = true;
        }
        catch (Exception e) when (e is IOException or AuthenticationException or OperationCanceledException or ObjectDisposedException)
        {
            LogConnectionFailed(remote, e);
        }
        finally
        {
            if (holdsPairingSlot)
            {
                _pairing.EndUnpairedSession();
            }

            if (!handedOver)
            {
                await ssl.DisposeAsync().ConfigureAwait(false);
                client.Dispose();
            }
        }
    }

    private void StartSession(SslStream ssl, PairedDevice device, IPEndPoint remote)
    {
        var session = new PeerSession(ssl, _time, _loggers.CreateLogger<PeerSession>());
        var info = new HostSessionInfo(device, remote, session);

        // §4.4: the newer connection wins once it has completed HELLO.
        _sessions.AddOrUpdate(
            device.DeviceId,
            info,
            (_, older) =>
            {
                older.Session.Close(new CloseReason.Superseded());
                return info;
            });

        session.Completion.ContinueWith(
            completed =>
            {
                _sessions.TryRemove(new KeyValuePair<string, HostSessionInfo>(device.DeviceId, info));
                if (completed.Result is CloseReason.Unpaired)
                {
                    _devices.Remove(device.DeviceId); // §10.4: the phone unpaired us.
                }

                SessionEnded?.Invoke(this, info);
            },
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);

        session.Start();
        LogSessionStarted(device.DeviceId, remote);
        SessionStarted?.Invoke(this, info);
    }

    private SslServerAuthenticationOptions ServerOptions() => new()
    {
        ServerCertificateContext = _certificateContext,
        ClientCertificateRequired = true,
#pragma warning disable CA5398 // PROTOCOL.md §4.2 pins exactly TLS 1.3 and 1.2; the OS default could include older versions.
        EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
#pragma warning restore CA5398
        CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
        AllowTlsResume = false,
        RemoteCertificateValidationCallback = ValidateClientCertificate,
    };

    /// <summary>
    /// Trust is the pin alone (§4.3): chain errors are expected for self-signed certificates and are
    /// ignored. A paired fingerprint is accepted; an unknown P-256 certificate only while pairing is open.
    /// </summary>
    private bool ValidateClientCertificate(object sender, X509Certificate? certificate, X509Chain? chain, SslPolicyErrors errors)
    {
        if (certificate is null)
        {
            return false;
        }

        byte[] raw = certificate.GetRawCertData();
        if (_devices.Find(DeviceIds.FromCertificate(raw)) is not null)
        {
            return true;
        }

        return _pairing.AcceptsUnknownClients && HasP256Key(raw);
    }

    private static bool HasP256Key(byte[] raw)
    {
        try
        {
            using X509Certificate2 certificate = X509CertificateLoader.LoadCertificate(raw);
            using ECDsa? key = certificate.GetECDsaPublicKey();
            return key?.ExportParameters(includePrivateParameters: false).Curve.Oid.Value == P256Oid;
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    private static bool NegotiatedWithinPolicy(SslStream ssl) =>
        ssl.SslProtocol is SslProtocols.Tls12 or SslProtocols.Tls13 && AllowedCipherSuites.Contains(ssl.NegotiatedCipherSuite);

    /// <summary>Reads until a <typeparamref name="T"/> arrives, answering unknown types (§15.2); anything else is refused.</summary>
    private async Task<T> ReceiveAsync<T>(SslStream ssl, TimeSpan timeout)
        where T : ProtocolMessage
    {
        using var deadline = new CancellationTokenSource(timeout, _time);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token, _stopping.Token);
        while (true)
        {
            ProtocolMessage message;
            try
            {
                message = MessageCodec.Decode(await FrameCodec.ReadFrameAsync(ssl, linked.Token).ConfigureAwait(false));
            }
            catch (ProtocolException e)
            {
                await SendErrorAsync(ssl, e.Code, e.Message).ConfigureAwait(false);
                throw;
            }

            switch (message)
            {
                case T expected:
                    return expected;
                case UnknownMessage unknown:
                    await SendErrorAsync(ssl, ErrorCode.UnknownType, string.Create(CultureInfo.InvariantCulture, $"Unknown frame type 0x{unknown.TypeCode:x2}")).ConfigureAwait(false);
                    break;
                case ErrorMessage error:
                    throw new ProtocolException(ErrorCodes.FromWireName(error.Code), $"Peer sent ERROR: {error.Message}");
                default:
                    await SendErrorAsync(ssl, ErrorCode.NotPaired, $"{message.GetType().Name} arrived during the handshake").ConfigureAwait(false);
                    throw new ProtocolException(ErrorCode.NotPaired, $"{message.GetType().Name} arrived during the handshake");
            }
        }
    }

    private static async Task WriteAsync(SslStream ssl, ProtocolMessage message)
    {
        await FrameCodec.WriteFrameAsync(ssl, MessageCodec.Encode(message)).ConfigureAwait(false);
        await ssl.FlushAsync().ConfigureAwait(false);
    }

    private static async Task SendErrorAsync(SslStream ssl, ErrorCode code, string detail)
    {
        try
        {
            await WriteAsync(ssl, new ErrorMessage(code.ToWireName(), detail.Length <= 256 ? detail : detail[..256])).ConfigureAwait(false);
        }
        catch (IOException)
        {
            // Best effort: the connection is closing anyway.
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Listening on port {Port}")]
    private partial void LogListening(int port);

    [LoggerMessage(Level = LogLevel.Information, Message = "Rejected connection from {Remote}: {Why}")]
    private partial void LogRejected(IPEndPoint remote, string why);

    [LoggerMessage(Level = LogLevel.Information, Message = "Pairing from {Remote} failed: {Attempt}")]
    private partial void LogPairingFailed(IPEndPoint remote, PairingAttempt attempt);

    [LoggerMessage(Level = LogLevel.Information, Message = "Paired with {DeviceId} ({Name})")]
    private partial void LogPaired(string deviceId, string name);

    [LoggerMessage(Level = LogLevel.Information, Message = "Session started with {DeviceId} from {Remote}")]
    private partial void LogSessionStarted(string deviceId, IPEndPoint remote);

    [LoggerMessage(Level = LogLevel.Debug, Message = "Connection from {Remote} ended during setup")]
    private partial void LogConnectionFailed(IPEndPoint remote, Exception error);
}
