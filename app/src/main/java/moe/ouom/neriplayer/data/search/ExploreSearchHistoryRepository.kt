package moe.ouom.neriplayer.data.search

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.Locale

private const val TAG = "ExploreSearchHistory"
private val HistoryKey = stringPreferencesKey("history_v1")

private val Context.exploreSearchHistoryDataStore by preferencesDataStore(
    name = "explore_search_history",
    corruptionHandler = ReplaceFileCorruptionHandler {
        NPLogger.e(TAG, "explore search history DataStore is corrupted", it)
        emptyPreferences()
    }
)

class ExploreSearchHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    val historyFlow: Flow<List<String>> = appContext.exploreSearchHistoryDataStore.data
        .map { prefs -> decodeHistory(prefs[HistoryKey]) }

    suspend fun record(query: String) {
        appContext.exploreSearchHistoryDataStore.edit { prefs ->
            val next = updatedExploreSearchHistory(decodeHistory(prefs[HistoryKey]), query)
            prefs[HistoryKey] = json.encodeToString(next)
        }
    }

    suspend fun clear() {
        appContext.exploreSearchHistoryDataStore.edit { prefs ->
            prefs.remove(HistoryKey)
        }
    }

    private fun decodeHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(raw)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(DEFAULT_EXPLORE_SEARCH_HISTORY_LIMIT)
        }.getOrElse { error ->
            NPLogger.w(TAG, "decode history failed: ${error.message}")
            emptyList()
        }
    }
}
