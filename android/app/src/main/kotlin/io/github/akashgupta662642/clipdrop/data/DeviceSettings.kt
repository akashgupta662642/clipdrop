package io.github.akashgupta662642.clipdrop.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** User settings stored in DataStore. The full settings screen arrives in a later milestone. */
@Singleton
class DeviceSettings @Inject constructor(@ApplicationContext private val context: Context) {
    /** The name other devices see; defaults to the phone's own device name. */
    val deviceName: Flow<String> = context.settingsStore.data.map { it[DEVICE_NAME] ?: systemDeviceName() }

    suspend fun deviceName(): String = deviceName.first()

    suspend fun setDeviceName(name: String) {
        context.settingsStore.edit { it[DEVICE_NAME] = name.trim().take(64) }
    }

    private fun systemDeviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private companion object {
        val DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
