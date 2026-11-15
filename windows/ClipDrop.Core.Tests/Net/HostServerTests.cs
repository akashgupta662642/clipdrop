using System.Net.Security;
using System.Security.Authentication;
using ClipDrop.Core.Identity;
using ClipDrop.Core.Net;
using ClipDrop.Core.Pairing;
using ClipDrop.Core.Protocol;
using ClipDrop.Core.Storage;
using Microsoft.Extensions.Time.Testing;

namespace ClipDrop.Core.Tests.Net;

/// <summary>The host over real loopback TLS (SChannel on Windows), with a test client playing the phone.</summary>
public sealed class HostServerTests : IAsyncLifetime
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;
    private readonly FakeTimeProvider _time = new(DateTimeOffset.UtcNow);
    private readonly InMemoryPairedDeviceStore _devices = new();
    private readonly HostIdentity _host = TestIdentities.Host();
    private readonly PairingCoordinator _pairing;
    private readonly HostServer _server;
    private readonly List<TestPhone> _phones = [];

    public HostServerTests()
    {
        _pairing = new PairingCoordinator(_time);
        _server = new HostServer(_host, _devices, _pairing, () => "Test PC", "0.1.0", _time);
    }

    public ValueTask InitializeAsync()
    {
        _server.Start([0]);
        return ValueTask.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        foreach (TestPhone phone in _phones)
        {
            await phone.DisposeAsync();
        }

        await _server.DisposeAsync();
        _host.Dispose();
    }

    private TestPhone NewPhone()
    {
        var phone = new TestPhone();
        _phones.Add(phone);
        return phone;
    }

    private void Pin(TestPhone phone) =>
        _devices.Upsert(new PairedDevice(phone.DeviceId, "Test Phone", "android", _time.GetUtcNow()));

    [Fact]
    public async Task A_paired_phone_gets_HELLO_ACK_paired_then_a_live_session()
    {
        TestPhone phone = NewPhone();
        Pin(phone);
        var started = new TaskCompletionSource<HostSessionInfo>();
        _server.SessionStarted += (_, info) => started.TrySetResult(info);

        await phone.ConnectAsync(_server.Port, _host.Fingerprint);
        await phone.SendAsync(phone.Hello());
        HelloAck ack = Assert.IsType<HelloAck>(await phone.ReceiveAsync());

        Assert.Equal(new HelloAck(1, _host.DeviceId, "Test PC", "windows", "0.1.0", Paired: true), ack);
        HostSessionInfo session = await started.Task.WaitAsync(Timeout, Ct);
        Assert.Equal(phone.DeviceId, session.Device.DeviceId);

        await phone.SendAsync(new Ping(9));
        Assert.Equal(new Pong(9), await phone.ReceiveAsync());
    }

    [Fact]
    public async Task The_negotiated_TLS_is_within_policy()
    {
        TestPhone phone = NewPhone();
        Pin(phone);
        await phone.ConnectAsync(_server.Port, _host.Fingerprint);

        Assert.True(phone.Stream.SslProtocol is SslProtocols.Tls12 or SslProtocols.Tls13);
        Assert.Contains("GCM", phone.Stream.NegotiatedCipherSuite.ToString(), StringComparison.Ordinal);
        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
        {
            Assert.Equal(SslProtocols.Tls13, phone.Stream.SslProtocol); // Windows 11 and later
        }
    }

    [Fact]
    public async Task An_unknown_phone_is_refused_while_pairing_is_closed()
    {
        Assert.True(await NewPhone().IsRefusedAsync(_server.Port, _host.Fingerprint));
        Assert.Empty(_server.Sessions);
    }

    [Fact]
    public async Task A_phone_without_a_client_certificate_is_refused()
    {
        _pairing.Open(); // Even pairing mode doesn't admit a missing certificate.
        Assert.True(await NewPhone().IsRefusedAsync(_server.Port, _host.Fingerprint, presentCertificate: false));
        Assert.Empty(_server.Sessions);
    }

    [Fact]
    public async Task Pairing_with_the_right_token_stores_the_phone_and_goes_live()
    {
        TestPhone phone = NewPhone();
        string token = _pairing.Open().Token;
        var paired = new TaskCompletionSource<PairedDevice>();
        _server.DevicePaired += (_, device) => paired.TrySetResult(device);

        await phone.ConnectAsync(_server.Port, _host.Fingerprint);
        await phone.SendAsync(phone.Hello());
        Assert.False(Assert.IsType<HelloAck>(await phone.ReceiveAsync()).Paired);
        await phone.SendAsync(new PairRequest(token));
        Assert.Equal(new PairResult(true), await phone.ReceiveAsync());

        Assert.Equal(phone.DeviceId, (await paired.Task.WaitAsync(Timeout, Ct)).DeviceId);
        Assert.NotNull(_devices.Find(phone.DeviceId));
        Assert.False(_pairing.IsOpen);

        await phone.SendAsync(new Ping(1));
        Assert.Equal(new Pong(1), await phone.ReceiveAsync());
    }

    [Fact]
    public async Task An_expired_token_gets_expired_token()
    {
        TestPhone phone = NewPhone();
        string token = _pairing.Open().Token;
        _time.Advance(TimeSpan.FromSeconds(121));

        await phone.ConnectAsync(_server.Port, _host.Fingerprint);
        await phone.SendAsync(phone.Hello());
        await phone.ReceiveAsync();
        await phone.SendAsync(new PairRequest(token));

        Assert.Equal(new PairResult(false, "expired_token"), await phone.ReceiveAsync());
        Assert.Null(_devices.Find(phone.DeviceId));
    }

    [Fact]
    public async Task A_wrong_token_gets_invalid_token_and_the_connection_closes()
    {
        TestPhone phone = NewPhone();
        _pairing.Open();

        await phone.ConnectAsync(_server.Port, _host.Fingerprint);
        await phone.SendAsync(phone.Hello());
        await phone.ReceiveAsync();
        await phone.SendAsync(new PairRequest("AAAAAAAAAAAAAAAAAAAAAA"));

        Assert.Equal(new PairResult(false, "invalid_token"), await phone.ReceiveAsync());
        await Assert.ThrowsAnyAsync<IOException>(phone.ReceiveAsync);
    }

    [Fact]
    public async Task A_HELLO_whose_device_id_isnt_the_certificate_gets_protocol_error()
    {
        TestPhone phone = NewPhone();
        Pin(phone);
        await phone.ConnectAsync(_server.Port, _host.Fingerprint);
        await phone.SendAsync(phone.Hello(deviceId: new string('B', 43)));

        Assert.Equal("protocol_error", Assert.IsType<ErrorMessage>(await phone.ReceiveAsync()).Code);
        Assert.Empty(_server.Sessions);
    }

    [Fact]
    public async Task A_newer_connection_replaces_the_older_session()
    {
        TestPhone first = NewPhone();
        Pin(first);
        await first.ConnectAsync(_server.Port, _host.Fingerprint);
        await first.SendAsync(first.Hello());
        await first.ReceiveAsync();

        // Same identity, new connection, as after a Wi-Fi switch.
        var again = new TestPhone(first.Certificate);
        _phones.Add(again);
        await again.ConnectAsync(_server.Port, _host.Fingerprint);
        await again.SendAsync(again.Hello());
        Assert.True(Assert.IsType<HelloAck>(await again.ReceiveAsync()).Paired);

        await Assert.ThrowsAnyAsync<IOException>(first.ReceiveAsync); // the old session was closed
        Assert.Single(_server.Sessions);
    }

    [Fact]
    public async Task UNPAIR_from_the_phone_forgets_it()
    {
        TestPhone phone = NewPhone();
        Pin(phone);
        var ended = new TaskCompletionSource();
        _server.SessionEnded += (_, _) => ended.TrySetResult();

        await phone.ConnectAsync(_server.Port, _host.Fingerprint);
        await phone.SendAsync(phone.Hello());
        await phone.ReceiveAsync();
        await phone.SendAsync(Unpair.Instance);

        await ended.Task.WaitAsync(Timeout, Ct);
        Assert.Null(_devices.Find(phone.DeviceId));
    }
}
