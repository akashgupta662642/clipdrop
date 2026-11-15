package io.github.akashgupta662642.clipdrop.engine.session

import io.github.akashgupta662642.clipdrop.protocol.ErrorCode
import io.github.akashgupta662642.clipdrop.protocol.ErrorMessage
import io.github.akashgupta662642.clipdrop.protocol.Frame
import io.github.akashgupta662642.clipdrop.protocol.Hello
import io.github.akashgupta662642.clipdrop.protocol.HelloAck
import io.github.akashgupta662642.clipdrop.protocol.Message
import io.github.akashgupta662642.clipdrop.protocol.MessageCodec
import io.github.akashgupta662642.clipdrop.protocol.PairRequest
import io.github.akashgupta662642.clipdrop.protocol.PairResult
import io.github.akashgupta662642.clipdrop.protocol.Ping
import io.github.akashgupta662642.clipdrop.protocol.Pong
import io.github.akashgupta662642.clipdrop.protocol.ProtocolConstants
import io.github.akashgupta662642.clipdrop.protocol.ProtocolException
import io.github.akashgupta662642.clipdrop.protocol.TransferChunk
import io.github.akashgupta662642.clipdrop.protocol.TransferEnd
import io.github.akashgupta662642.clipdrop.protocol.UnknownMessage
import io.github.akashgupta662642.clipdrop.protocol.Unpair
import io.github.akashgupta662642.clipdrop.protocol.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Why a session ended. */
public sealed interface CloseReason {
    /** We closed it. */
    public data object Local : CloseReason

    /** The peer closed the connection. */
    public data object RemoteClosed : CloseReason

    /** Nothing arrived for [ProtocolConstants.HEARTBEAT_TIMEOUT] (§9.4). */
    public data object HeartbeatTimeout : CloseReason

    /** The peer sent UNPAIR (§10.4). */
    public data object Unpaired : CloseReason

    /** The peer broke the protocol; we sent it ERROR with [code]. */
    public data class ProtocolViolation(val code: ErrorCode, val detail: String) : CloseReason

    /** The peer sent ERROR with [code] and we closed. */
    public data class PeerError(val code: ErrorCode, val detail: String) : CloseReason

    public data class IoFailure(val error: IOException) : CloseReason
}

/** Thrown by [PeerSession.send] once the session has closed. */
public class SessionClosedException(public val reason: CloseReason?) : IOException("Session closed: $reason")

/**
 * A live session (PROTOCOL.md §9) over one connection, after HELLO and any pairing are done.
 *
 * One reader coroutine decodes frames, answers PING, and hands every other message to [incoming].
 * One writer coroutine drains two queues (§9.5): control frames always go first, and the data queue
 * holds at most [ProtocolConstants.DATA_QUEUE_CAPACITY] frames, so [send] suspends to give file
 * readers backpressure. A heartbeat coroutine sends PING after [heartbeatIdle] of silence and closes
 * the session after [heartbeatTimeout].
 */
public class PeerSession(
    private val transport: FrameTransport,
    scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val heartbeatIdle: Duration = ProtocolConstants.HEARTBEAT_IDLE,
    private val heartbeatTimeout: Duration = ProtocolConstants.HEARTBEAT_TIMEOUT,
    private val random: Random = Random.Default,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + job)

    private val control = Channel<Frame>(Channel.UNLIMITED)
    private val data = Channel<Frame>(ProtocolConstants.DATA_QUEUE_CAPACITY)
    private val incomingChannel = Channel<Message>(Channel.UNLIMITED)
    private val pendingPings = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<CloseReason>()

    @Volatile
    private var lastReceived: ComparableTimeMark = timeSource.markNow()

    @Volatile
    private var finalReason: CloseReason? = null
    private var writer: Job? = null

    /** Every message other than PING, PONG and ERROR-that-closes, in arrival order. Collect once. */
    public val incoming: Flow<Message> = incomingChannel.receiveAsFlow()

    /** Completes with the reason once the session has fully closed. */
    public val closeReason: Deferred<CloseReason> get() = closed

    public val isOpen: Boolean get() = !closing.get()

    /** Starts the reader, writer and heartbeat. Call once. */
    public fun start() {
        writer = sessionScope.launch { writeLoop() }
        sessionScope.launch { readLoop() }
        sessionScope.launch { heartbeatLoop() }
    }

    /**
     * Queues [message]. Chunks and TRANSFER_END go to the bounded data queue, in order, and may
     * suspend; everything else goes to the control queue.
     */
    public suspend fun send(message: Message) {
        val frame = MessageCodec.encode(message)
        try {
            if (message is TransferChunk || message is TransferEnd) data.send(frame) else control.send(frame)
        } catch (_: ClosedSendChannelException) {
            throw SessionClosedException(finalReason)
        }
    }

    /** Sends PING and suspends until the matching PONG arrives; returns the round-trip time. */
    public suspend fun ping(): Duration {
        val nonce = random.nextLong(0, Validation.MAX_NONCE + 1)
        val answer = CompletableDeferred<Unit>()
        pendingPings[nonce] = answer
        val sentAt = timeSource.markNow()
        try {
            send(Ping(nonce))
            answer.await()
            return sentAt.elapsedNow()
        } finally {
            pendingPings.remove(nonce)
        }
    }

    /** Sends UNPAIR, flushes it (best effort, at most 1 s), and closes (§10.4). */
    public suspend fun unpair() {
        sendThenClose(MessageCodec.encode(Unpair), CloseReason.Local)
    }

    /** Closes immediately. Idempotent. */
    public fun close() {
        teardown(CloseReason.Local)
    }

    private suspend fun writeLoop() {
        try {
            while (true) {
                val frame = control.tryReceive().getOrNull() ?: select {
                    // select is biased to the first clause, so control still wins ties.
                    control.onReceiveCatching { it.getOrNull() }
                    data.onReceive { it }
                } ?: break // The control queue was closed for a flush: everything is written.
                transport.writeFrame(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            teardown(CloseReason.IoFailure(e))
        }
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val frame = transport.readFrame()
                lastReceived = timeSource.markNow()
                if (!handle(MessageCodec.decode(frame))) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtocolException) {
            sendThenClose(errorFrame(e.code, e.message.orEmpty()), CloseReason.ProtocolViolation(e.code, e.message.orEmpty()))
        } catch (_: EOFException) {
            teardown(CloseReason.RemoteClosed)
        } catch (e: IOException) {
            teardown(CloseReason.IoFailure(e))
        }
    }

    /** Handles one inbound message; returns false once the session is closing. */
    private suspend fun handle(message: Message): Boolean {
        when (message) {
            is Ping -> control.trySend(MessageCodec.encode(Pong(message.nonce)))
            is Pong -> pendingPings[message.nonce]?.complete(Unit)
            is UnknownMessage -> control.trySend(
                errorFrame(ErrorCode.UNKNOWN_TYPE, "Unknown frame type 0x%02x".format(message.typeCode)),
            )
            is ErrorMessage -> {
                val code = ErrorCode.fromWireName(message.code)
                if (code != ErrorCode.UNKNOWN_TYPE) {
                    teardown(CloseReason.PeerError(code, message.message))
                    return false
                }
                incomingChannel.trySend(message) // §15.2: carry on, and let the feature fall back.
            }
            is Hello, is HelloAck, is PairRequest, is PairResult -> {
                val detail = "${message.javaClass.simpleName} is not allowed in a live session"
                sendThenClose(errorFrame(ErrorCode.PROTOCOL_ERROR, detail), CloseReason.ProtocolViolation(ErrorCode.PROTOCOL_ERROR, detail))
                return false
            }
            is Unpair -> {
                teardown(CloseReason.Unpaired)
                return false
            }
            else -> incomingChannel.trySend(message)
        }
        return true
    }

    private suspend fun heartbeatLoop() {
        var lastPingSent: ComparableTimeMark? = null
        while (true) {
            delay(HEARTBEAT_TICK)
            val idle = lastReceived.elapsedNow()
            if (idle >= heartbeatTimeout) {
                teardown(CloseReason.HeartbeatTimeout)
                return
            }
            val sinceLastPing = lastPingSent?.elapsedNow()
            if (idle >= heartbeatIdle && (sinceLastPing == null || sinceLastPing >= heartbeatIdle)) {
                control.trySend(MessageCodec.encode(Ping(random.nextLong(0, Validation.MAX_NONCE + 1))))
                lastPingSent = timeSource.markNow()
            }
        }
    }

    /** Queues [frame] last, lets the writer drain the control queue (at most 1 s), then closes. */
    private suspend fun sendThenClose(frame: Frame, reason: CloseReason) {
        if (closing.get()) return
        control.trySend(frame)
        control.close()
        withTimeoutOrNull(FLUSH_TIMEOUT) { writer?.join() }
        teardown(reason)
    }

    private fun teardown(reason: CloseReason) {
        if (!closing.compareAndSet(false, true)) return
        finalReason = reason
        transport.close()
        control.close()
        data.close()
        incomingChannel.close()
        pendingPings.values.forEach { it.completeExceptionally(SessionClosedException(reason)) }
        closed.complete(reason)
        job.cancel()
    }

    private fun errorFrame(code: ErrorCode, detail: String): Frame =
        MessageCodec.encode(ErrorMessage(code.wireName, detail.take(256)))

    private companion object {
        val HEARTBEAT_TICK = 1.seconds
        val FLUSH_TIMEOUT = 1.seconds
    }
}
