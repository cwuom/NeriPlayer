package moe.ouom.neriplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import moe.ouom.neriplayer.ksp.annotations.SettingValueType

class AutoSettingSpecRepository(private val context: Context) {
    fun <T> flow(setting: AutoSettingSpec<T>): Flow<T> {
        return context.autoSettingFlow(setting)
    }

    suspend fun <T> set(setting: AutoSettingSpec<T>, value: T) {
        context.setAutoSetting(setting, value)
    }
}

@Suppress("UNCHECKED_CAST")
val <T> AutoSettingSpec<T>.preferencesKey: Preferences.Key<T>
    get() {
        return when (type) {
            SettingValueType.Boolean -> booleanPreferencesKey(key)
            SettingValueType.Float -> floatPreferencesKey(key)
            SettingValueType.Int -> intPreferencesKey(key)
            SettingValueType.Long -> longPreferencesKey(key)
            SettingValueType.String -> stringPreferencesKey(key)
        } as Preferences.Key<T>
    }

fun <T> Preferences.valueOf(setting: AutoSettingSpec<T>): T {
    return this[setting.preferencesKey] ?: setting.defaultValue
}

operator fun <T> MutablePreferences.set(setting: AutoSettingSpec<T>, value: T) {
    this[setting.preferencesKey] = value
}

fun <T> Context.autoSettingFlow(setting: AutoSettingSpec<T>): Flow<T> {
    return dataStore.data.map { prefs ->
        prefs.valueOf(setting)
    }.distinctUntilChanged()
}

suspend fun <T> Context.setAutoSetting(setting: AutoSettingSpec<T>, value: T) {
    dataStore.edit { prefs ->
        prefs[setting] = value
    }
}
