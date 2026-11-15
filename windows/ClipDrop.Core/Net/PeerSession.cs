using System.Collections.Concurrent;
using System.Globalization;
using System.Security.Cryptography;
using System.Threading.Channels;
using ClipDrop.Core.Protocol;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipDrop.Core.Net;

/// <summary>Why a session ended.</summary>
public abstract record CloseReason
{
    public sealed record Local : CloseReason;

    public sealed record RemoteClosed : CloseReason;

    public sealed record HeartbeatTimeout : CloseReason;

    public sealed record Unpaired : CloseReason;

    /// <summary>Replaced by a newer connection from the same device (PROTOCOL.md §4.4).</summary>
    public sealed record Superseded : CloseReason;

    /// <summary>The peer broke the protocol; we sent it ERROR with <see cref="Code"/>.</summary>
    public sealed record ProtocolViolation(ErrorCode Code, string Detail) : CloseReason;

    /// <summary>The peer sent ERROR with <see cref="Code"/> and we closed.</summary>
    public sealed record PeerError(ErrorCode Code, string Detail) : CloseReason;

    public sealed record IoFailure(Exception Error) : CloseReason;
}

/// <summary>Thrown by <see cref="PeerSession.SendAsync"/> once the session has closed.</summary>
public sealed class SessionClosedException(CloseReason? reason)
    : IOException($"Session closed: {reason}")
{
    public CloseReason? Reason { get; } = reason;
}

/// <summary>
/// A live session (PROTOCOL.md §9) over one stream, after HELLO and any pairing are done. Mirrors the
/// Android engine's PeerSession.
/// <para>
/// One reader task decodes frames, answers PING and hands every other message to <see cref="Incoming"/>.
/// One writer task drains two queues (§9.5): control frames always go first, and the data queue holds
/// at most <see cref="ProtocolConstants.DataQueueCapacity"/> frames, so <see cref="SendAsync"/> waits to
/// give file readers backpressure. A heartbeat sends PING after 15 s of silence and closes after 45 s.
/// </para>
/// </summary>
public sealed partial class PeerSession : IAsyncDisposable
{
    private static readonly TimeSpan HeartbeatTick = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan FlushTimeout = TimeSpan.FromSeconds(1);

    private readonly Stream _stream;
    private readonly TimeProvider _time;
    private readonly ILogger _logger;
    private readonly TimeSpan _heartbeatIdle;
    private readonly TimeSpan _heartbeatTimeout;
    private readonly Channel<Frame> _control = Channel.CreateUnbounded<Frame>(new UnboundedChannelOptions { SingleReader = true });
    private readonly Channel<Frame> _data = Channel.CreateBounded<Frame>(
        new BoundedChannelOptions(ProtocolConstants.DataQueueCapacity) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private readonly Channel<ProtocolMessage> _incoming = Channel.CreateUnbounded<ProtocolMessage>();
    private readonly ConcurrentDictionary<long, TaskCompletionSource> _pendingPings = new();
    private readonly TaskCompletionSource<CloseReason> _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly CancellationTokenSource _lifetime = new();
    private int _closing;
    private long _lastReceived;
    private Task _writer = Task.CompletedTask;
    private CloseReason? _finalReason;

    public PeerSession(
        Stream stream,
        TimeProvider timeProvider,
        ILogger? logger = null,
        TimeSpan? heartbeatIdle = null,
        TimeSpan? heartbeatTimeout = null)
    {
        _stream = stream;
        _time = timeProvider;
        _logger = logger ?? NullLogger.Instance;
        _heartbeatIdle = heartbeatIdle ?? ProtocolConstants.HeartbeatIdle;
        _heartbeatTimeout = heartbeatTimeout ?? ProtocolConstants.HeartbeatTimeout;
        _lastReceived = _time.GetTimestamp();
    }

    /// <summary>Every message other than PING, PONG and an ERROR that closes the session, in arrival order.</summary>
    public ChannelReader<ProtocolMessage> Incoming => _incoming.Reader;

    /// <summary>Completes with the reason once the session has fully closed.</summary>
    public Task<CloseReason> Completion => _closed.Task;

    public bool IsOpen => Volatile.Read(ref _closing) == 0;

    /// <summary>Starts the reader, writer and heartbeat. Call once.</summary>
    public void Start()
    {
        // Arm the heartbeat timer here, not inside the task: the clock must be counting from the moment
        // Start returns, however long the thread pool takes to run the loop.
        var heartbeat = new PeriodicTimer(HeartbeatTick, _time);
        _writer = Task.Run(WriteLoopAsync);
        _ = Task.Run(ReadLoopAsync);
        _ = Task.Run(() => HeartbeatLoopAsync(heartbeat));
    }

    /// <summary>
    /// Queues <paramref name="message"/>. Chunks and TRANSFER_END go to the bounded data queue, in
    /// order, and may wait; everything else goes to the control queue.
    /// </summary>
    public async ValueTask SendAsync(ProtocolMessage message, CancellationToken cancellationToken = default)
    {
        Frame frame = MessageCodec.Encode(message);
        try
        {
            if (message is TransferChunk or TransferEnd)
            {
                await _data.Writer.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            }
            else
            {
                await _control.Writer.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (ChannelClosedException)
        {
            throw new SessionClosedException(_finalReason);
        }
    }

    /// <summary>Sends PING and waits for the matching PONG; returns the round-trip time.</summary>
    public async Task<TimeSpan> PingAsync(CancellationToken cancellationToken = default)
    {
        long nonce = NewNonce();
        var answer = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingPings[nonce] = answer;
        long sentAt = _time.GetTimestamp();
        try
        {
            await SendAsync(new Ping(nonce), cancellationToken).ConfigureAwait(false);
            await answer.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
            return _time.GetElapsedTime(sentAt);
        }
        finally
        {
            _pendingPings.TryRemove(nonce, out _);
        }
    }

    /// <summary>Sends UNPAIR, flushes it (best effort, at most 1 s), and closes (§10.4).</summary>
    public Task UnpairAsync() => SendThenCloseAsync(MessageCodec.Encode(Unpair.Instance), new CloseReason.Local());

    /// <summary>Closes immediately with <paramref name="reason"/> (default: local). Idempotent.</summary>
    public void Close(CloseReason? reason = null) => Teardown(reason ?? new CloseReason.Local());

    public async ValueTask DisposeAsync()
    {
        Close();
        await _closed.Task.ConfigureAwait(false);
        _lifetime.Dispose();
    }

    private async Task WriteLoopAsync()
    {
        CancellationToken ct = _lifetime.Token;
        try
        {
            while (true)
            {
                if (_control.Reader.TryRead(out Frame? frame))
                {
                    await WriteAsync(frame, ct).ConfigureAwait(false);
                    continue;
                }

                if (_control.Reader.Completion.IsCompleted)
                {
                    return; // The control queue was completed for a flush and is now drained.
                }

                if (_data.Reader.TryRead(out frame))
                {
                    await WriteAsync(frame, ct).ConfigureAwait(false);
                    continue;
                }

                Task<bool> control = _control.Reader.WaitToReadAsync(ct).AsTask();
                Task<bool> data = _data.Reader.WaitToReadAsync(ct).AsTask();
                await Task.WhenAny(control, data).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // Torn down.
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException or ChannelClosedException)
        {
            Teardown(new CloseReason.IoFailure(e));
        }
    }

    private async Task WriteAsync(Frame frame, CancellationToken ct)
    {
        await FrameCodec.WriteFrameAsync(_stream, frame, ct).ConfigureAwait(false);
        await _stream.FlushAsync(ct).ConfigureAwait(false);
    }

    private async Task ReadLoopAsync()
    {
        CancellationToken ct = _lifetime.Token;
        try
        {
            while (true)
            {
                Frame frame = await FrameCodec.ReadFrameAsync(_stream, ct).ConfigureAwait(false);
                Interlocked.Exchange(ref _lastReceived, _time.GetTimestamp());
                if (!await HandleAsync(MessageCodec.Decode(frame)).ConfigureAwait(false))
                {
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Torn down.
        }
        catch (ProtocolException e)
        {
            LogProtocolViolation(e.Code, e.Message);
            await SendThenCloseAsync(ErrorFrame(e.Code, e.Message), new CloseReason.ProtocolViolation(e.Code, e.Message)).ConfigureAwait(false);
        }
        catch (EndOfStreamException)
        {
            Teardown(new CloseReason.RemoteClosed());
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException)
        {
            Teardown(new CloseReason.IoFailure(e));
        }
    }

    /// <summary>Handles one inbound message; returns false once the session is closing.</summary>
    private async ValueTask<bool> HandleAsync(ProtocolMessage message)
    {
        switch (message)
        {
            case Ping ping:
                _control.Writer.TryWrite(MessageCodec.Encode(new Pong(ping.Nonce)));
                return true;
            case Pong pong:
                if (_pendingPings.TryGetValue(pong.Nonce, out TaskCompletionSource? waiter))
                {
                    waiter.TrySetResult();
                }

                return true;
            case UnknownMessage unknown:
                _control.Writer.TryWrite(ErrorFrame(ErrorCode.UnknownType, string.Create(CultureInfo.InvariantCulture, $"Unknown frame type 0x{unknown.TypeCode:x2}")));
                return true;
            case ErrorMessage error:
                ErrorCode code = ErrorCodes.FromWireName(error.Code);
                if (code != ErrorCode.UnknownType)
                {
                    Teardown(new CloseReason.PeerError(code, error.Message));
                    return false;
                }

                _incoming.Writer.TryWrite(error); // §15.2: carry on, and let the feature fall back.
                return true;
            case Hello or HelloAck or PairRequest or PairResult:
                string detail = $"{message.GetType().Name} is not allowed in a live session";
                await SendThenCloseAsync(ErrorFrame(ErrorCode.ProtocolError, detail), new CloseReason.ProtocolViolation(ErrorCode.ProtocolError, detail)).ConfigureAwait(false);
                return false;
            case Unpair:
                Teardown(new CloseReason.Unpaired());
                return false;
            default:
                _incoming.Writer.TryWrite(message);
                return true;
        }
    }

    private async Task HeartbeatLoopAsync(PeriodicTimer timer)
    {
        long? lastPingSent = null;
        using PeriodicTimer owned = timer;
        try
        {
            while (await timer.WaitForNextTickAsync(_lifetime.Token).ConfigureAwait(false))
            {
                TimeSpan idle = _time.GetElapsedTime(Interlocked.Read(ref _lastReceived));
                if (idle >= _heartbeatTimeout)
                {
                    Teardown(new CloseReason.HeartbeatTimeout());
                    return;
                }

                if (idle >= _heartbeatIdle && (lastPingSent is null || _time.GetElapsedTime(lastPingSent.Value) >= _heartbeatIdle))
                {
                    _control.Writer.TryWrite(MessageCodec.Encode(new Ping(NewNonce())));
                    lastPingSent = _time.GetTimestamp();
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Torn down.
        }
    }

    /// <summary>Queues <paramref name="frame"/> last, lets the writer drain the control queue (at most 1 s), then closes.</summary>
    private async Task SendThenCloseAsync(Frame frame, CloseReason reason)
    {
        if (!IsOpen)
        {
            return;
        }

        _control.Writer.TryWrite(frame);
        _control.Writer.TryComplete();
        await Task.WhenAny(_writer, Task.Delay(FlushTimeout, _time)).ConfigureAwait(false);
        Teardown(reason);
    }

    private void Teardown(CloseReason reason)
    {
        if (Interlocked.Exchange(ref _closing, 1) != 0)
        {
            return;
        }

        _finalReason = reason;
        _lifetime.Cancel();
        _stream.Dispose();
        _control.Writer.TryComplete();
        _data.Writer.TryComplete();
        _incoming.Writer.TryComplete();
        foreach (TaskCompletionSource waiter in _pendingPings.Values)
        {
            waiter.TrySetException(new SessionClosedException(reason));
        }

        LogClosed(reason);
        _closed.TrySetResult(reason);
    }

    private static Frame ErrorFrame(ErrorCode code, string detail) =>
        MessageCodec.Encode(new ErrorMessage(code.ToWireName(), detail.Length <= 256 ? detail : detail[..256]));

    private static long NewNonce() => BitConverter.ToUInt32(RandomNumberGenerator.GetBytes(4));

    [LoggerMessage(Level = LogLevel.Information, Message = "Session closed: {Reason}")]
    private partial void LogClosed(CloseReason reason);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Peer broke the protocol ({Code}): {Detail}")]
    private partial void LogProtocolViolation(ErrorCode code, string detail);
}
