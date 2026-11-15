package io.github.akashgupta662642.clipdrop.engine.clipboard

import io.github.akashgupta662642.clipdrop.protocol.ProtocolConstants
import java.security.MessageDigest

/**
 * Remembers the hash of the last clip exchanged with a peer, sent or received, so it is never sent
 * straight back (PROTOCOL.md §11.3). Thread-safe. [capacity] exists for tests; the protocol uses 1.
 *
 * Text is compared after normalising line endings to `\n`: Windows and Android disagree about
 * CRLF, and a clip must still be recognised after a round trip through either clipboard.
 */
public class EchoGuard(private val capacity: Int = ProtocolConstants.ECHO_HISTORY) {
    private val recent = ArrayDeque<String>(capacity)

    /** Records [text] as the last clip exchanged (sent to, or received from, a peer). */
    public fun remember(text: String) {
        val hash = hashOf(text)
        synchronized(recent) {
            recent.remove(hash)
            recent.addLast(hash)
            while (recent.size > capacity) recent.removeFirst()
        }
    }

    /** True if [text] matches a recently remembered clip, so sending it would only echo it back. */
    public fun isEcho(text: String): Boolean = synchronized(recent) { hashOf(text) in recent }

    private fun hashOf(text: String): String {
        val normalised = text.replace("\r\n", "\n").encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(normalised).joinToString("") { "%02x".format(it) }
    }
}
