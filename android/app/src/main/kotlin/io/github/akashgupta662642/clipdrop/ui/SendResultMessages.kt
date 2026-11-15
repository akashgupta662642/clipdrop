package io.github.akashgupta662642.clipdrop.ui

import android.content.Context
import io.github.akashgupta662642.clipdrop.R
import io.github.akashgupta662642.clipdrop.clipboard.SendResult
import io.github.akashgupta662642.clipdrop.engine.clipboard.OutgoingClip

/** The user-facing outcome of sending the clipboard, for a toast or snackbar. */
fun SendResult.message(context: Context): String = when (this) {
    is SendResult.Sent -> context.getString(R.string.send_sent_to, hostNames.joinToString(", "))
    SendResult.NoPcConnected -> context.getString(R.string.send_no_pc)
    is SendResult.Skipped -> context.getString(
        when (reason) {
            OutgoingClip.Skipped.EMPTY -> R.string.send_empty
            OutgoingClip.Skipped.SENSITIVE -> R.string.send_sensitive
            OutgoingClip.Skipped.ECHO -> R.string.send_echo
            OutgoingClip.Skipped.TOO_LARGE -> R.string.send_too_large
            OutgoingClip.Skipped.NOT_TEXT -> R.string.send_not_text
        },
    )
}
