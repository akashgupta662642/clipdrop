package io.github.akashgupta662642.clipdrop

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.akashgupta662642.clipdrop.clipboard.ClipboardCoordinator
import javax.inject.Inject

@HiltAndroidApp
class ClipDropApplication : Application() {
    /** Created at process start so clips from a PC are handled whenever a session is live. */
    @Inject
    lateinit var clipboardCoordinator: ClipboardCoordinator
}
