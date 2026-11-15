package io.github.akashgupta662642.clipdrop.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akashgupta662642.clipdrop.engine.clipboard.LocalClip
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android clipboard.
 *
 * Writing works from the background service. Reading only works while one of this app's windows has
 * input focus (Android 10+): in a service, or even in an activity's onResume(), getPrimaryClip()
 * returns null. That's why sending from the phone is always a user action that briefly shows
 * [SendClipboardActivity].
 */
@Singleton
class ClipboardBridge @Inject constructor(@ApplicationContext private val context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** Puts text from a PC on the clipboard, marked as coming from another device. */
    fun write(text: String) {
        val clip = ClipData.newPlainText(LABEL, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // A rendering hint (API 34+): the system may present remote clips differently.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true) }
        }
        clipboard.setPrimaryClip(clip)
    }

    /** Reads the current clip. Call only while an activity of this app has window focus. */
    fun read(): LocalClip {
        val clip = clipboard.primaryClip ?: return LocalClip.Empty
        if (clip.itemCount == 0) return LocalClip.Empty
        val description = clip.description
        val item = clip.getItemAt(0)
        val isText = description.hasMimeType("text/*") || (item.text != null && item.uri == null)
        if (!isText) return LocalClip.NotText
        val text = item.coerceToText(context)?.toString() ?: return LocalClip.Empty
        return LocalClip.Text(text, sensitive = isSensitive(description))
    }

    private fun isSensitive(description: ClipDescription): Boolean =
        // The constant is API 33, but password managers set the same key on older versions too.
        description.extras?.getBoolean(EXTRA_IS_SENSITIVE_KEY, false) == true

    private companion object {
        const val LABEL = "ClipDrop"
        const val EXTRA_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"
    }
}
