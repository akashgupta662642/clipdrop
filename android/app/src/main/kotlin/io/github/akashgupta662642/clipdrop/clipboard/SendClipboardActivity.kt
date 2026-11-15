package io.github.akashgupta662642.clipdrop.clipboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.akashgupta662642.clipdrop.ui.message
import javax.inject.Inject

/**
 * Invisible and instant: reads the clipboard the moment it gains window focus, hands the clip to
 * [ClipboardCoordinator], and finishes. Android 10+ only lets an app read the clipboard while it
 * has input focus, which a service never has, and which an activity doesn't have yet in onResume().
 * The tile, the notification action and the in-app button all lead here or do the same.
 */
@AndroidEntryPoint
class SendClipboardActivity : ComponentActivity() {
    @Inject
    lateinit var bridge: ClipboardBridge

    @Inject
    lateinit var coordinator: ClipboardCoordinator

    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val clip = bridge.read() // Only valid now, while this window has focus.
        val app = applicationContext
        coordinator.sendInBackground(clip) { result ->
            Toast.makeText(app, result.message(app), Toast.LENGTH_SHORT).show()
        }
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, SendClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
