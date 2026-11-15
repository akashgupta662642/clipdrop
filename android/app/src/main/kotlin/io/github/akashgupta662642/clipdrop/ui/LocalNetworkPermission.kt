package io.github.akashgupta662642.clipdrop.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) blocks LAN traffic for apps that target it unless the user grants
 * ACCESS_LOCAL_NETWORK, a runtime permission in the NEARBY_DEVICES group. Older platforms grant
 * LAN access with INTERNET, so there is nothing to ask.
 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val FIRST_ENFORCING_SDK = 37

    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= FIRST_ENFORCING_SDK

    fun isGranted(context: Context): Boolean =
        !isRequired || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}

/** Whether local network access is granted, and a function that asks for it. */
class PermissionHandle(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberLocalNetworkPermission(): PermissionHandle {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalNetworkPermission.isGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return PermissionHandle(granted) {
        if (LocalNetworkPermission.isRequired) launcher.launch(LocalNetworkPermission.NAME) else granted = true
    }
}
