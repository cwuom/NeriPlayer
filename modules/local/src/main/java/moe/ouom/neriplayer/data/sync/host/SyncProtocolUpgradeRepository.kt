package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Locale
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
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

    // 文件替换失败后内存缓存可能已变化，所有许可读取都核对正式文件
    private val verifiedFlow = dataStore.data.map { verifiedPreferences() }

    val approvedFlow: Flow<Boolean> = verifiedFlow
        .map { supported(it) && pending(it).isEmpty() && startupTargets(it).isEmpty() }
        .distinctUntilChanged()

    val startupPendingFlow: Flow<Set<String>> = verifiedFlow
        .map { ensureSupported(it); startupPromptTargets(it) }
        .distinctUntilChanged()

    val pendingFlow: Flow<List<SyncProtocolUpgradeChallenge>> = verifiedFlow
        .map { ensureSupported(it); pending(it) }
        .distinctUntilChanged()

    val pendingChallengeFlow: Flow<SyncProtocolUpgradeChallenge?> = pendingFlow
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    fun versionFlow(targetId: String): Flow<Int> = verifiedFlow.map { preferences ->
        val storedVersion = storedProtocolVersion(preferences)
        if (storedVersion > CURRENT_PROTOCOL_VERSION) storedVersion
        else if (targetId in startupTargets(preferences) || preferences[pendingKey(targetId)] != null || preferences[approvalKey(targetId)] != null) LEGACY_PROTOCOL_VERSION
        else CURRENT_PROTOCOL_VERSION
    }.distinctUntilChanged()

    suspend fun initializeStartupTargets(targetIds: Set<String>) {
        val targets = targetIds.toSet()
        require(targets.all { it.matches(TargetHash) }) { "Invalid sync target" }
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            if (preferences[StartupRegistrationVersion] == null) {
                // 只登记首次新版启动前已有的配置，新地址仍等待真实远端格式检测
                preferences[StartupLegacyTargets] = targets.filterTo(mutableSetOf()) { target ->
                    target !in preferences[ObservedCurrentTargets].orEmpty() &&
                        preferences[pendingKey(target)] == null && preferences[approvalKey(target)] == null
                }
                preferences[StartupRegistrationVersion] = CURRENT_PROTOCOL_VERSION
            }
        }
        ensureSupported(verifiedPreferences())
    }

    suspend fun completeStartupUpgrade(targetId: String) {
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            preferences[StartupLegacyTargets] = startupTargets(preferences) - targetId
        }
        ensureSupported(verifiedPreferences())
    }

    suspend fun requireLegacyMigration(challenge: SyncProtocolUpgradeChallenge) {
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            if (preferences[approvalKey(challenge.targetId)] != challenge.fingerprint) {
                preferences[pendingKey(challenge.targetId)] = challenge.fingerprint
                preferences.remove(approvalKey(challenge.targetId))
            }
        }
        val preferences = verifiedPreferences()
        ensureSupported(preferences)
        if (preferences[approvalKey(challenge.targetId)] != challenge.fingerprint) {
            throw SyncProtocolUpgradeRequiredException(requiredMessage(), challenge)
        }
    }

    suspend fun confirmAllDevicesUpdated(allDevicesUpdated: Boolean, challenge: SyncProtocolUpgradeChallenge) {
        require(allDevicesUpdated) { "All sync devices must be updated before upgrading the sync database" }
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            val detected = preferences[pendingKey(challenge.targetId)]
            if (detected != challenge.fingerprint) {
                val current = detected?.let { SyncProtocolUpgradeChallenge(challenge.targetId, it) }
                throw SyncProtocolUpgradeRequiredException(requiredMessage(), current)
            }
            preferences[approvalKey(challenge.targetId)] = challenge.fingerprint
            preferences.remove(pendingKey(challenge.targetId))
            preferences[ApprovedProtocolVersion] = CURRENT_PROTOCOL_VERSION
        }
        val confirmed = verifiedPreferences()
        ensureSupported(confirmed)
        if (confirmed[approvalKey(challenge.targetId)] != challenge.fingerprint) {
            throw SyncProtocolUpgradeRequiredException(requiredMessage(), challenge)
        }
    }

    suspend fun confirmAllDevicesUpdated(allDevicesUpdated: Boolean) {
        require(allDevicesUpdated) { "All sync devices must be updated before upgrading the sync database" }
        val challenge = pendingFlow.first().singleOrNull()
            ?: throw IllegalStateException("Confirm a detected sync database upgrade")
        confirmAllDevicesUpdated(true, challenge)
    }

    suspend fun markCurrent(targetId: String) {
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            preferences.remove(pendingKey(targetId))
            preferences.remove(approvalKey(targetId))
            preferences[StartupLegacyTargets] = startupTargets(preferences) - targetId
            preferences[ObservedCurrentTargets] = preferences[ObservedCurrentTargets].orEmpty() + targetId
        }
        ensureSupported(verifiedPreferences())
    }

    suspend fun canSyncTarget(targetId: String): Boolean {
        val preferences = verifiedPreferences()
        return supported(preferences) && targetId !in startupTargets(preferences) && preferences[pendingKey(targetId)] == null
    }

    suspend fun <T> executeIfApproved(action: suspend () -> Result<T>): Result<T> = try {
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        val result = action()
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        result
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun verifiedPreferences(): Preferences = dataStore.updateData { it }

    private fun supported(preferences: Preferences): Boolean =
        storedProtocolVersion(preferences) <= CURRENT_PROTOCOL_VERSION

    private fun storedProtocolVersion(preferences: Preferences): Int = maxOf(
        preferences[ApprovedProtocolVersion] ?: CURRENT_PROTOCOL_VERSION,
        preferences[StartupRegistrationVersion] ?: CURRENT_PROTOCOL_VERSION
    )

    private fun startupTargets(preferences: Preferences): Set<String> =
        preferences[StartupLegacyTargets].orEmpty()

    private fun startupPromptTargets(preferences: Preferences): Set<String> = startupTargets(preferences) +
        preferences.asMap().keys.filter { it.name.startsWith(ApprovalPrefix) }
            .map { it.name.removePrefix(ApprovalPrefix) }

    private fun ensureSupported(preferences: Preferences) {
        if (!supported(preferences)) throw SyncProtocolUpgradeRequiredException(requiredMessage())
    }

    private fun pending(preferences: Preferences): List<SyncProtocolUpgradeChallenge> = preferences.asMap()
        .filterKeys { it.name.startsWith(PendingPrefix) }
        .map { (key, value) -> SyncProtocolUpgradeChallenge(key.name.removePrefix(PendingPrefix),
            value as? String ?: throw IOException("Invalid sync upgrade state")) }
        .sortedBy { it.targetId }

    private fun pendingKey(targetId: String) = stringPreferencesKey("$PendingPrefix$targetId")
    private fun approvalKey(targetId: String) = stringPreferencesKey("$ApprovalPrefix$targetId")

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 3
        const val LEGACY_PROTOCOL_VERSION = 0
        private val ApprovedProtocolVersion = intPreferencesKey("approved_protocol_version")
        private val StartupRegistrationVersion = intPreferencesKey("startup_registration_version")
        private val StartupLegacyTargets = stringSetPreferencesKey("startup_legacy_targets")
        private val ObservedCurrentTargets = stringSetPreferencesKey("observed_current_targets")
        private val TargetHash = Regex("[0-9a-f]{64}")
        private const val PendingPrefix = "pending_legacy_"
        private const val ApprovalPrefix = "approved_legacy_"

        fun githubTargetHash(owner: String, repo: String): String = targetHash(
            listOf("github", owner.trim().lowercase(Locale.ROOT), repo.trim().lowercase(Locale.ROOT))
        )

        fun webDavTargetHash(serverUrl: String, basePath: String, username: String): String = targetHash(
            listOf("webdav", WebDavApiClient.buildRemoteFileUrl(serverUrl, basePath), username)
        )

        private fun targetHash(parts: List<String>): String = WebDavApiClient.calculateFingerprint(
            parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        )

        private fun localizedRequiredMessage(context: Context): () -> String = {
            LanguageManager.applyLanguage(context).getString(CoreCommonR.string.sync_upgrade_required)
        }
    }
}
