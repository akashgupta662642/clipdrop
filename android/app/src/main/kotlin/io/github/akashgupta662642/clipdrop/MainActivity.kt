package io.github.akashgupta662642.clipdrop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import io.github.akashgupta662642.clipdrop.ui.ClipDropApp
import io.github.akashgupta662642.clipdrop.ui.theme.ClipDropTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A `clipdrop://pair` link waiting to be shown on the pairing screen. */
    private var pairingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pairingLink = pairingLinkFrom(intent)
        setContent {
            ClipDropTheme {
                ClipDropApp(pairingLink = pairingLink, onPairingLinkHandled = { pairingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairingLinkFrom(intent)?.let { pairingLink = it }
    }

    private fun pairingLinkFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
}
