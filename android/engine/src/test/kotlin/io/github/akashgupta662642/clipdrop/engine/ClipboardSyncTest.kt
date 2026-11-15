package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.clipboard.ClipboardSync
import io.github.akashgupta662642.clipdrop.engine.clipboard.EchoGuard
import io.github.akashgupta662642.clipdrop.engine.clipboard.LocalClip
import io.github.akashgupta662642.clipdrop.engine.clipboard.OutgoingClip
import io.github.akashgupta662642.clipdrop.protocol.ClipText
import io.github.akashgupta662642.clipdrop.protocol.ProtocolConstants
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClipboardSyncTest {
    private val clipId = "3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162"
    private val sync = ClipboardSync(now = { 1_000 }, newId = { clipId })

    @Test
    fun `plain text becomes a CLIP_TEXT`() {
        assertEquals(OutgoingClip.Send(ClipText(clipId, "hello", 1_000)), sync.prepare(LocalClip.Text("hello", sensitive = false)))
    }

    @Test
    fun `empty, blank, non-text and sensitive clips are not sent`() {
        assertEquals(OutgoingClip.Skipped.EMPTY, sync.prepare(LocalClip.Empty))
        assertEquals(OutgoingClip.Skipped.EMPTY, sync.prepare(LocalClip.Text(" \n\t ", sensitive = false)))
        assertEquals(OutgoingClip.Skipped.NOT_TEXT, sync.prepare(LocalClip.NotText))
        assertEquals(OutgoingClip.Skipped.SENSITIVE, sync.prepare(LocalClip.Text("hunter2", sensitive = true)))
    }

    @Test
    fun `a clip that came from the peer is never sent back`() {
        sync.onReceived(ClipText(clipId, "from the PC\r\nsecond line", 1))
        // The phone's clipboard may hand it back with LF line endings: still recognised.
        assertEquals(OutgoingClip.Skipped.ECHO, sync.prepare(LocalClip.Text("from the PC\nsecond line", sensitive = false)))
        assertIs<OutgoingClip.Send>(sync.prepare(LocalClip.Text("something new", sensitive = false)))
    }

    @Test
    fun `text too large for one frame is refused, not truncated`() {
        val huge = "x".repeat(ProtocolConstants.MAX_FRAME_LENGTH)
        assertEquals(OutgoingClip.Skipped.TOO_LARGE, sync.prepare(LocalClip.Text(huge, sensitive = false)))
    }

    @Test
    fun `the echo guard forgets old clips beyond its capacity`() {
        val guard = EchoGuard(capacity = 3)
        listOf("a", "b", "c", "d").forEach(guard::remember)
        assertFalse(guard.isEcho("a"))
        assertTrue(guard.isEcho("b") && guard.isEcho("c") && guard.isEcho("d"))

        guard.remember("b") // refreshing moves it to the back
        guard.remember("e")
        assertTrue(guard.isEcho("b"))
        assertFalse(guard.isEcho("c"))
    }
}

class ReconnectBackoffTest {
    @Test
    fun `delays double from one second to a thirty second cap`() {
        val backoff = ReconnectBackoff(random = FixedRandom(0.5)) // factor exactly 1.0
        val delays = List(8) { backoff.nextDelay().inWholeSeconds }
        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30, 30), delays)
    }

    @Test
    fun `jitter stays within plus or minus twenty percent`() {
        val backoff = ReconnectBackoff(random = Random(42))
        repeat(50) { assertTrue(backoff.nextDelay() in 0.8.seconds..36.seconds) }
    }

    @Test
    fun `reset starts again from one second`() {
        val backoff = ReconnectBackoff(random = FixedRandom(0.5))
        repeat(5) { backoff.nextDelay() }
        backoff.reset()
        assertEquals(1, backoff.nextDelay().inWholeSeconds)
    }

    /** nextDouble(from, until) returns the point [fraction] of the way through the range. */
    private class FixedRandom(private val fraction: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(from: Double, until: Double): Double = from + (until - from) * fraction
    }
}
