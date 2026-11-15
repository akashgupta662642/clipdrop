using ClipDrop.Core.Net;
using ClipDrop.Core.Protocol;
using Microsoft.Extensions.Time.Testing;

namespace ClipDrop.Core.Tests.Net;

public sealed class PeerSessionTests : IAsyncLifetime
{
    private const string TransferId = "a3bb189e-8bf9-4888-9912-ace4e6543002";
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;
    private readonly FakeTimeProvider _time = new();
    private SocketPair _sockets = null!;
    private GatedStream _local = null!;
    private PeerSession _session = null!;

    public async ValueTask InitializeAsync()
    {
        _sockets = await SocketPair.CreateAsync();
        _local = new GatedStream(_sockets.A);
        _session = new PeerSession(_local, _time);
        _session.Start();
    }

    public async ValueTask DisposeAsync()
    {
        await _session.DisposeAsync();
        _sockets.Dispose();
    }

    private Task SendFromPeerAsync(ProtocolMessage message) => SendFrameFromPeerAsync(MessageCodec.Encode(message));

    private async Task SendFrameFromPeerAsync(Frame frame)
    {
        await FrameCodec.WriteFrameAsync(_sockets.B, frame, Ct);
        await _sockets.B.FlushAsync(Ct);
    }

    private async Task<ProtocolMessage> ReceiveAtPeerAsync() =>
        MessageCodec.Decode(await FrameCodec.ReadFrameAsync(_sockets.B, Ct).AsTask().WaitAsync(Timeout, Ct));

    [Fact]
    public async Task Answers_PING_with_PONG_carrying_the_same_nonce()
    {
        await SendFromPeerAsync(new Ping(77));
        Assert.Equal(new Pong(77), await ReceiveAtPeerAsync());
    }

    [Fact]
    public async Task PingAsync_returns_once_the_matching_PONG_arrives()
    {
        Task<TimeSpan> rtt = _session.PingAsync(Ct);
        Ping ping = Assert.IsType<Ping>(await ReceiveAtPeerAsync());
        _time.Advance(TimeSpan.FromMilliseconds(250));
        await SendFromPeerAsync(new Pong(ping.Nonce));
        Assert.Equal(TimeSpan.FromMilliseconds(250), await rtt.WaitAsync(Timeout, Ct));
    }

    [Fact]
    public async Task Delivers_application_messages_to_Incoming()
    {
        var clip = new ClipText("3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162", "hello", 1);
        await SendFromPeerAsync(clip);
        Assert.Equal(clip, await _session.Incoming.ReadAsync(Ct).AsTask().WaitAsync(Timeout, Ct));
    }

    [Fact]
    public async Task Sends_PING_after_15_seconds_of_silence_and_closes_after_45()
    {
        _time.Advance(TimeSpan.FromSeconds(16));
        Assert.IsType<Ping>(await ReceiveAtPeerAsync());

        for (int i = 0; i < 30; i++)
        {
            _time.Advance(TimeSpan.FromSeconds(1));
        }

        Assert.IsType<CloseReason.HeartbeatTimeout>(await _session.Completion.WaitAsync(Timeout, Ct));
    }

    [Fact]
    public async Task Control_frames_overtake_queued_data_frames()
    {
        await _local.Gate.WaitAsync(Ct); // Hold the writer while frames queue up.
        await _session.SendAsync(new TransferChunk(TransferId, 0, new byte[] { 1 }), Ct);
        await WaitUntilAsync(() => _local.WaitingWriters == 1); // The writer took chunk 0 and is held.
        await _session.SendAsync(new TransferChunk(TransferId, 1, new byte[] { 1 }), Ct);
        await _session.SendAsync(new TransferChunk(TransferId, 2, new byte[] { 1 }), Ct);
        await _session.SendAsync(new TransferCancel(TransferId), Ct);
        _local.Gate.Release();

        var order = new List<string>();
        for (int i = 0; i < 4; i++)
        {
            order.Add(await ReceiveAtPeerAsync() switch
            {
                TransferChunk chunk => $"chunk{chunk.Offset}",
                ProtocolMessage other => other.GetType().Name,
            });
        }

        Assert.Equal(["chunk0", "TransferCancel", "chunk1", "chunk2"], order);
    }

    [Fact]
    public async Task Unknown_frame_types_get_an_ERROR_reply_and_the_session_carries_on()
    {
        await SendFrameFromPeerAsync(new Frame(0x42, new byte[] { 1, 2 }));
        Assert.Equal("unknown_type", Assert.IsType<ErrorMessage>(await ReceiveAtPeerAsync()).Code);

        await SendFromPeerAsync(new Ping(1));
        Assert.Equal(new Pong(1), await ReceiveAtPeerAsync());
        Assert.True(_session.IsOpen);
    }

    [Fact]
    public async Task A_malformed_frame_gets_ERROR_protocol_error_then_the_session_closes()
    {
        await SendFrameFromPeerAsync(new Frame((byte)MessageType.Ping, """{"nonce":"1"}"""u8.ToArray()));
        Assert.Equal("protocol_error", Assert.IsType<ErrorMessage>(await ReceiveAtPeerAsync()).Code);
        Assert.IsType<CloseReason.ProtocolViolation>(await _session.Completion.WaitAsync(Timeout, Ct));
    }

    [Fact]
    public async Task Peer_closing_the_connection_ends_the_session()
    {
        _sockets.B.Close();
        Assert.IsType<CloseReason.RemoteClosed>(await _session.Completion.WaitAsync(Timeout, Ct));
    }

    [Fact]
    public async Task UnpairAsync_sends_UNPAIR_before_closing()
    {
        await _session.UnpairAsync();
        Assert.Equal(Unpair.Instance, await ReceiveAtPeerAsync());
        Assert.IsType<CloseReason.Local>(await _session.Completion.WaitAsync(Timeout, Ct));
    }

    private static async Task WaitUntilAsync(Func<bool> condition)
    {
        for (int i = 0; i < 500 && !condition(); i++)
        {
            await Task.Delay(10, Ct);
        }

        Assert.True(condition(), "condition not reached within 5 s");
    }
}
