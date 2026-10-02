package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException

private val Context.syncProtocolUpgradeDataStore by preferencesDataStore("sync_protocol_upgrade")

class SyncProtocolUpgradeRepository(
    private val dataStore: DataStore<Preferences>,
    private val requiredMessage: () -> String = {
        "Confirm that all sync devices are updated before upgrading the sync database"
    }
) {
    constructor(context: Context) : this(
        context.applicationContext.syncProtocolUpgradeDataStore,
        localizedRequiredMessage(context.applicationContext)
    )

    val approvedFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            // 文件替换失败后内存缓存可能已变化，批准还需要确认正式文件
            preferences[ApprovedProtocolVersion] == CURRENT_PROTOCOL_VERSION &&
                dataStore.updateData { it }[ApprovedProtocolVersion] == CURRENT_PROTOCOL_VERSION
        }
        .distinctUntilChanged()

    suspend fun confirmAllDevicesUpdated(allDevicesUpdated: Boolean) {
        require(allDevicesUpdated) { "All sync devices must be updated before upgrading the sync database" }
        currentCoroutineContext().ensureActive()
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            val approvedVersion = preferences[ApprovedProtocolVersion]
            if (approvedVersion != null && approvedVersion > CURRENT_PROTOCOL_VERSION) {
                throw SyncProtocolUpgradeRequiredException(requiredMessage())
            }
            preferences[ApprovedProtocolVersion] = CURRENT_PROTOCOL_VERSION
        }
    }

    suspend fun <T> executeIfApproved(action: suspend () -> Result<T>): Result<T> {
        return try {
            currentCoroutineContext().ensureActive()
            if (!approvedFlow.first()) {
                Result.failure(SyncProtocolUpgradeRequiredException(requiredMessage()))
            } else {
                val result = action()
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 3
        private val ApprovedProtocolVersion = intPreferencesKey("approved_protocol_version")

        private fun localizedRequiredMessage(context: Context): () -> String = {
            LanguageManager.applyLanguage(context).getString(CoreCommonR.string.sync_upgrade_required)
        }
    }
}
