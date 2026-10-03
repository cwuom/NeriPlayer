package moe.ouom.neriplayer.core.startup.debug

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val debugBuildWarningCorruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.debugBuildWarningDataStore by preferencesDataStore(
    name = "debug_build_warning",
    corruptionHandler = debugBuildWarningCorruptionHandler
)

internal class DebugBuildWarningRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.debugBuildWarningDataStore)

    private val acknowledgedKey = booleanPreferencesKey("acknowledged")

    val acknowledgedFlow = dataStore.data.map { it[acknowledgedKey] ?: false }

    suspend fun isAcknowledged(): Boolean = acknowledgedFlow.first()

    suspend fun acknowledge() {
        dataStore.edit { it[acknowledgedKey] = true }
    }
}
