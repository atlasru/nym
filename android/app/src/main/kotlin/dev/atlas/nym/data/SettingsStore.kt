package dev.atlas.nym.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.atlas.nym.core.ScanConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsData by preferencesDataStore("nym-settings-v1")
@Serializable data class Preferences(val scan: ScanConfig = ScanConfig(), val theme: String = "dark", val reducedMotion: Boolean = false)

class SettingsStore(context: Context, private val crypto: AppCrypto) {
    private val data = context.settingsData
    private val key = stringPreferencesKey("preferences")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val flow: Flow<Preferences> = data.data.map { prefs -> prefs[key]?.let { json.decodeFromString<Preferences>(crypto.decode(it)) } ?: Preferences() }
    suspend fun save(preferences: Preferences) {
        preferences.scan.validate()
        data.edit { it[key] = crypto.encode(json.encodeToString(preferences)) }
    }
}
