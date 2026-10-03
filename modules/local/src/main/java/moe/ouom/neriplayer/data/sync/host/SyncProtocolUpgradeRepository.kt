package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage

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
        else migrationForTarget(preferences, targetId)?.fromVersion
            ?: observedVersion(preferences, targetId)
            ?: if (targetId in startupTargets(preferences)) LEGACY_PROTOCOL_VERSION else CURRENT_PROTOCOL_VERSION
    }.distinctUntilChanged()

    suspend fun initializeStartupTargets(targetIds: Set<String>) {
        val targets = targetIds.toSet()
        require(targets.all { it.matches(TargetHash) }) { "Invalid sync target" }
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            migrateObservedTargets(preferences)
            if ((preferences[StartupRegistrationVersion] ?: 0) < CURRENT_PROTOCOL_VERSION) {
                // 每次协议升级登记已有地址，新配置仍等待真实远端检测
                preferences[StartupLegacyTargets] = startupTargets(preferences) + targets.filter { target ->
                    observedVersion(preferences, target) != CURRENT_PROTOCOL_VERSION &&
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
        ensureSupportedChallenge(challenge)
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            preferences[observedKey(challenge.targetId)] = challenge.fromVersion
            if (preferences[approvalKey(challenge.targetId)] != encodeChallenge(challenge)) {
                preferences[pendingKey(challenge.targetId)] = encodeChallenge(challenge)
                preferences.remove(approvalKey(challenge.targetId))
            }
        }
        val preferences = verifiedPreferences()
        ensureSupported(preferences)
        if (preferences[approvalKey(challenge.targetId)] != encodeChallenge(challenge)) {
            throw SyncProtocolUpgradeRequiredException(requiredMessage(), challenge)
        }
    }

    suspend fun confirmAllDevicesUpdated(allDevicesUpdated: Boolean, challenge: SyncProtocolUpgradeChallenge) {
        require(allDevicesUpdated) { "All sync devices must be updated before upgrading the sync database" }
        ensureSupportedChallenge(challenge)
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            val detected = preferences[pendingKey(challenge.targetId)]
            if (detected?.let { decodeChallenge(challenge.targetId, it) } != challenge) {
                val current = detected?.let { decodeChallenge(challenge.targetId, it) }
                throw SyncProtocolUpgradeRequiredException(requiredMessage(), current)
            }
            preferences[approvalKey(challenge.targetId)] = encodeChallenge(challenge)
            preferences.remove(pendingKey(challenge.targetId))
            preferences[ApprovedProtocolVersion] = CURRENT_PROTOCOL_VERSION
        }
        val confirmed = verifiedPreferences()
        ensureSupported(confirmed)
        if (confirmed[approvalKey(challenge.targetId)] != encodeChallenge(challenge)) {
            throw SyncProtocolUpgradeRequiredException(requiredMessage(), challenge)
        }
    }

    suspend fun confirmAllDevicesUpdated(allDevicesUpdated: Boolean) {
        require(allDevicesUpdated) { "All sync devices must be updated before upgrading the sync database" }
        val challenge = pendingFlow.first().singleOrNull()
            ?: throw IllegalStateException("Confirm a detected sync database upgrade")
        confirmAllDevicesUpdated(true, challenge)
    }

    suspend fun markCurrent(targetId: String, version: Int = CURRENT_PROTOCOL_VERSION) {
        require(targetId.matches(TargetHash)) { "Invalid sync target" }
        require(version == 3 || version == CURRENT_PROTOCOL_VERSION) { "Unsupported observed sync protocol" }
        currentCoroutineContext().ensureActive()
        ensureSupported(verifiedPreferences())
        dataStore.edit { preferences ->
            currentCoroutineContext().ensureActive()
            ensureSupported(preferences)
            preferences[observedKey(targetId)] = version
            if (version == CURRENT_PROTOCOL_VERSION) {
                preferences.remove(pendingKey(targetId))
                preferences.remove(approvalKey(targetId))
                preferences[StartupLegacyTargets] = startupTargets(preferences) - targetId
            }
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
        preferences[StartupRegistrationVersion] ?: CURRENT_PROTOCOL_VERSION,
        storedMigrationProtocolVersion(preferences),
        storedObservedProtocolVersion(preferences)
    )

    private fun storedMigrationProtocolVersion(preferences: Preferences): Int =
        migrationRecords(preferences).maxOfOrNull { it.toVersion } ?: CURRENT_PROTOCOL_VERSION

    private fun storedObservedProtocolVersion(preferences: Preferences): Int = preferences.asMap()
        .filterKeys { it.name.startsWith(ObservedProtocolPrefix) }.values
        .map { it as? Int ?: throw IOException("Invalid observed sync protocol") }
        .maxOrNull() ?: CURRENT_PROTOCOL_VERSION

    private fun ensureSupportedChallenge(challenge: SyncProtocolUpgradeChallenge) {
        if (challenge.toVersion != CURRENT_PROTOCOL_VERSION || challenge.fromVersion !in listOf(LEGACY_PROTOCOL_VERSION, 3)) {
            throw SyncProtocolUpgradeRequiredException(requiredMessage(), challenge)
        }
    }

    private fun observedVersion(preferences: Preferences, targetId: String): Int? =
        preferences[observedKey(targetId)] ?: targetId.takeIf { it in preferences[ObservedCurrentTargets].orEmpty() }?.let { 3 }

    private fun migrateObservedTargets(preferences: MutablePreferences) {
        for (target in preferences[ObservedCurrentTargets].orEmpty()) {
            require(target.matches(TargetHash)) { "Invalid observed sync target" }
            if (preferences[observedKey(target)] == null) preferences[observedKey(target)] = 3
        }
    }

    private fun migrationForTarget(preferences: Preferences, targetId: String): SyncProtocolUpgradeChallenge? =
        (preferences[pendingKey(targetId)] ?: preferences[approvalKey(targetId)])?.let { decodeChallenge(targetId, it) }

    private fun migrationRecords(preferences: Preferences): List<SyncProtocolUpgradeChallenge> = preferences.asMap()
        .filterKeys { it.name.startsWith(PendingPrefix) || it.name.startsWith(ApprovalPrefix) }
        .map { (key, value) ->
            val target = key.name.removePrefix(PendingPrefix).removePrefix(ApprovalPrefix)
            decodeChallenge(target, value as? String ?: throw IOException("Invalid sync upgrade state"))
        }

    private fun encodeChallenge(challenge: SyncProtocolUpgradeChallenge): String =
        "${challenge.fromVersion}:${challenge.toVersion}:${challenge.fingerprint}"

    private fun decodeChallenge(targetId: String, value: String): SyncProtocolUpgradeChallenge {
        if (value.matches(TargetHash)) return SyncProtocolUpgradeChallenge(targetId, value)
        val parts = value.split(':')
        if (parts.size != 3) throw IOException("Invalid sync upgrade state")
        val from = parts[0].toIntOrNull() ?: throw IOException("Invalid sync upgrade source version")
        val to = parts[1].toIntOrNull() ?: throw IOException("Invalid sync upgrade destination version")
        return SyncProtocolUpgradeChallenge(targetId, parts[2], from, to)
    }

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
        .map { (key, value) -> decodeChallenge(key.name.removePrefix(PendingPrefix),
            value as? String ?: throw IOException("Invalid sync upgrade state")) }
        .sortedBy { it.targetId }

    private fun pendingKey(targetId: String) = stringPreferencesKey("$PendingPrefix$targetId")
    private fun approvalKey(targetId: String) = stringPreferencesKey("$ApprovalPrefix$targetId")
    private fun observedKey(targetId: String) = intPreferencesKey("$ObservedProtocolPrefix$targetId")

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 4
        const val LEGACY_PROTOCOL_VERSION = 0
        private val ApprovedProtocolVersion = intPreferencesKey("approved_protocol_version")
        private val StartupRegistrationVersion = intPreferencesKey("startup_registration_version")
        private val StartupLegacyTargets = stringSetPreferencesKey("startup_legacy_targets")
        private val ObservedCurrentTargets = stringSetPreferencesKey("observed_current_targets")
        private val TargetHash = Regex("[0-9a-f]{64}")
        private const val PendingPrefix = "pending_legacy_"
        private const val ApprovalPrefix = "approved_legacy_"
        private const val ObservedProtocolPrefix = "observed_protocol_"

        fun configuredTargetIds(context: Context): Set<String> = buildSet {
            val github = SecureTokenStorage(context)
            if (github.isConfigured()) {
                val configuration = github.snapshot()
                add(githubTargetHash(configuration.repoOwner, configuration.repoName))
            }
            val webDav = WebDavStorage(context)
            if (webDav.isConfigured()) {
                val configuration = webDav.snapshot()
                add(webDavTargetHash(configuration.serverUrl, configuration.basePath, configuration.username))
            }
        }

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
