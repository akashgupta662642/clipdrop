package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.protocol.ProtocolConstants
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Reconnect delays from PROTOCOL.md §9.6: 1 s doubling up to 30 s, each multiplied by a random
 * factor in [0.8, 1.2] so many phones don't retry in lockstep. Not thread-safe; one per host.
 */
public class ReconnectBackoff(
    private val initial: Duration = ProtocolConstants.RECONNECT_BACKOFF_INITIAL,
    private val max: Duration = ProtocolConstants.RECONNECT_BACKOFF_MAX,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    /** The delay before the next attempt; each call doubles the base, up to [max]. */
    public fun nextDelay(): Duration {
        val base = if (attempt >= MAX_SHIFT) max else minOf(max, initial * (1 shl attempt))
        attempt++
        return base * random.nextDouble(0.8, 1.2)
    }

    /** After a successful connection, or when Wi-Fi comes back, start again from [initial]. */
    public fun reset() {
        attempt = 0
    }

    private companion object {
        /** 2^5 s already exceeds 30 s; stop shifting before the multiplier can overflow. */
        const val MAX_SHIFT = 16
    }
}
