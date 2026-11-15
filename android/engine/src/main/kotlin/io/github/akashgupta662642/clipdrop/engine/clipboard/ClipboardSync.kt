package io.github.akashgupta662642.clipdrop.engine.clipboard

import io.github.akashgupta662642.clipdrop.protocol.ClipText
import io.github.akashgupta662642.clipdrop.protocol.MessageCodec
import java.util.UUID

/** What the local clipboard held when the user asked to send it. */
public sealed interface LocalClip {
    public data class Text(val text: String, val sensitive: Boolean) : LocalClip

    /** An image, file or other non-text clip: not sent as text (images arrive in a later milestone). */
    public data object NotText : LocalClip

    public data object Empty : LocalClip
}

/** The decision for one outgoing clip (PROTOCOL.md §11.1 and §11.3). */
public sealed interface OutgoingClip {
    public data class Send(val message: ClipText) : OutgoingClip

    public enum class Skipped : OutgoingClip {
        /** Nothing on the clipboard, or only whitespace. */
        EMPTY,

        /** Marked sensitive by the app that copied it (a password manager, for example). */
        SENSITIVE,

        /** It came from the peer in the first place. */
        ECHO,

        /** Too large for one frame (§7). */
        TOO_LARGE,

        /** Not text. */
        NOT_TEXT,
    }
}

/**
 * Platform-independent clipboard rules: what may be sent, and what to do with what arrives. The
 * platform code reads and writes the real clipboard; this class decides.
 */
public class ClipboardSync(
    private val echoGuard: EchoGuard = EchoGuard(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Decides whether [clip] is sent, and builds the CLIP_TEXT if so. */
    public fun prepare(clip: LocalClip): OutgoingClip {
        val text = when (clip) {
            LocalClip.Empty -> return OutgoingClip.Skipped.EMPTY
            LocalClip.NotText -> return OutgoingClip.Skipped.NOT_TEXT
            is LocalClip.Text -> clip
        }
        return when {
            text.text.isBlank() -> OutgoingClip.Skipped.EMPTY
            text.sensitive -> OutgoingClip.Skipped.SENSITIVE
            echoGuard.isEcho(text.text) -> OutgoingClip.Skipped.ECHO
            else -> {
                val message = ClipText(newId(), text.text, now())
                if (MessageCodec.fitsInFrame(message)) OutgoingClip.Send(message) else OutgoingClip.Skipped.TOO_LARGE
            }
        }
    }

    /** Call when [clip] arrives from a peer, before writing it to the local clipboard. */
    public fun onReceived(clip: ClipText) {
        echoGuard.remember(clip.text)
    }
}
