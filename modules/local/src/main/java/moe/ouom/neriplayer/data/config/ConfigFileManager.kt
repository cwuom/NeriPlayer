package moe.ouom.neriplayer.data.config

import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import moe.ouom.neriplayer.data.model.config.AppConfigBackup
import moe.ouom.neriplayer.data.model.config.AppConfigImportResult
import moe.ouom.neriplayer.data.model.config.LanguageConfigSnapshot
import moe.ouom.neriplayer.data.model.config.SavedCookieConfigSnapshot
import moe.ouom.neriplayer.data.model.config.TypedPreferenceSnapshot
import moe.ouom.neriplayer.data.model.config.YouTubeAuthConfigSnapshot

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.bootstrap.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.playback.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.appearance.persistThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.bootstrap.toBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.playback.toPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.sync.store.preferences.SyncPreferences
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.logging.NPLogger

class ConfigFileManager(
    private val context: Context,
    listenTogetherPreferences: ListenTogetherPreferences? = null,
    neteaseCookieRepo: NeteaseCookieRepository? = null,
    biliCookieRepo: BiliCookieRepository? = null,
    youTubeAuthRepo: YouTubeAuthRepository? = null,
    upgradeRepository: SyncProtocolUpgradeRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val listenTogetherRepository by lazy {
        listenTogetherPreferences ?: ListenTogetherPreferences(context)
    }
    private val neteaseRepository by lazy { neteaseCookieRepo ?: NeteaseCookieRepository(context) }
    private val biliRepository by lazy { biliCookieRepo ?: BiliCookieRepository(context) }
    private val youTubeRepository by lazy { youTubeAuthRepo ?: YouTubeAuthRepository(context) }
    private val syncUpgradeRepository by lazy { upgradeRepository ?: SyncProtocolUpgradeRepository(context) }

    companion object {
        private const val TAG = "ConfigFileManager"
        private const val MAX_CONFIG_IMPORT_BYTES = 2L * 1024L * 1024L
    }

    suspend fun exportConfig(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val settingsPrefs = context.dataStore.data.first()
            val gitHubStorage = SecureTokenStorage(context)
            val payload = AppConfigBackup(
                exportedAt = System.currentTimeMillis(),
                settings = settingsPrefs.toTypedPreferenceSnapshot(),
                listenTogether = listenTogetherRepository.snapshot(),
                language = LanguageConfigSnapshot(
                    code = LanguageManager.getCurrentLanguage(context).code
                ),
                neteaseAuth = neteaseRepository.run {
                    SavedCookieConfigSnapshot(
                        cookies = getCookiesOnce(),
                        savedAt = getAuthHealthOnce().savedAt
                    )
                },
                biliAuth = biliRepository.run {
                    SavedCookieConfigSnapshot(
                        cookies = getCookiesOnce(),
                        savedAt = getAuthHealthOnce().savedAt
                    )
                },
                youTubeAuth = youTubeRepository.getAuthOnce().toConfigSnapshot(),
                gitHubSync = gitHubStorage.snapshot(),
                webDavSync = WebDavStorage(context).snapshot(),
                syncPreferences = SyncPreferences(context).snapshot(
                    gitHubStorage.getLegacyPlayHistoryUpdateModeName()
                )
            )

            val encoded = AppConfigBackupCodec.encode(payload)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(encoded)
            } ?: throw IllegalStateException(context.getString(CoreCommonR.string.error_cannot_open_output))

            val fileName = DocumentFile.fromSingleUri(context, uri)?.name
                ?.takeIf { it.isNotBlank() }
                ?: AppConfigBackupCodec.generateFileName(payload.exportedAt)
            Result.success(fileName)
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to export config file", e)
            Result.failure(e)
        }
    }

    suspend fun importConfig(uri: Uri): Result<AppConfigImportResult> = withContext(ioDispatcher) {
        try {
            val raw = LimitedTextReader.readUtf8(context, uri, MAX_CONFIG_IMPORT_BYTES)
            val decoded = AppConfigBackupCodec.decodeForImport(raw)
            val payload = decoded.payload
            val sections = decoded.sections
            SyncCoordinator.withExclusive {
                if (sections.gitHubSync || sections.webDavSync) {
                    // 先登记导入前的地址，不能把新配置误认成旧安装已有的同步目标
                    syncUpgradeRepository.initializeStartupTargets(
                        SyncProtocolUpgradeRepository.configuredTargetIds(context)
                    )
                    currentCoroutineContext().ensureActive()
                }
                val warnings = mutableListOf<String>()

                val sanitizedSettings = if (sections.settings) {
                    ConfigSettingsSanitizer(context).sanitize(payload.settings, warnings)
                } else {
                    TypedPreferenceSnapshot()
                }
                if (sections.settings) {
                    restoreSettings(sanitizedSettings)
                }
                if (sections.listenTogether) {
                    listenTogetherRepository.restore(payload.listenTogether)
                }

                val currentLanguage = LanguageManager.getCurrentLanguage(context)
                val importedLanguage = if (sections.language) {
                    payload.language.toLanguageOrNull()
                } else {
                    null
                }
                if (importedLanguage != null) {
                    LanguageManager.setLanguage(context, importedLanguage)
                }

                val restoredAuthCount = restoreAuth(payload, sections, warnings)
                val gitHubStorage = SecureTokenStorage(context)
                val webDavStorage = WebDavStorage(context)
                val syncPreferences = SyncPreferences(context)
                val hasLegacyPlayHistoryMode = sections.gitHubSync &&
                    payload.gitHubSync.playHistoryUpdateMode.isNotBlank()
                if (sections.syncPreferences || hasLegacyPlayHistoryMode) {
                    syncPreferences.restore(
                        snapshot = payload.syncPreferences,
                        legacyModeName = payload.gitHubSync.playHistoryUpdateMode
                    )
                }
                if (sections.gitHubSync) {
                    gitHubStorage.restore(payload.gitHubSync)
                }
                if (sections.webDavSync) {
                    webDavStorage.restore(payload.webDavSync)
                }
                if (sections.hasSyncSection) {
                    gitHubStorage.markSyncMutation()
                }
                if (sections.gitHubSync || sections.webDavSync) {
                    reconcileSyncWorkers(gitHubStorage, webDavStorage)
                }

                Result.success(
                    AppConfigImportResult(
                        restoredSettingsCount = sanitizedSettings.entryCount(),
                        restoredListenTogetherCount = if (sections.listenTogether) {
                            payload.listenTogether.entryCount()
                        } else {
                            0
                        },
                        restoredAuthCount = restoredAuthCount,
                        restoredSyncCount = payload.syncSectionCount(sections),
                        warnings = warnings,
                        requiresActivityRecreate = importedLanguage != null && importedLanguage != currentLanguage
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to import config file", e)
            Result.failure(e)
        }
    }

    fun generateBackupFileName(): String = AppConfigBackupCodec.generateFileName()

    private suspend fun restoreSettings(snapshot: TypedPreferenceSnapshot) {
        context.dataStore.edit { prefs ->
            SETTINGS_BOOLEAN_KEYS.forEach { key ->
                snapshot.booleans[key.name]?.let { prefs[key] = it } ?: prefs.remove(key)
            }
            SETTINGS_FLOAT_KEYS.forEach { key ->
                snapshot.floats[key.name]?.let { prefs[key] = it } ?: prefs.remove(key)
            }
            SETTINGS_INT_KEYS.forEach { key ->
                snapshot.ints[key.name]?.let { prefs[key] = it } ?: prefs.remove(key)
            }
            SETTINGS_LONG_KEYS.forEach { key ->
                snapshot.longs[key.name]?.let { prefs[key] = it } ?: prefs.remove(key)
            }
            SETTINGS_STRING_KEYS.forEach { key ->
                snapshot.strings[key.name]?.let { prefs[key] = it } ?: prefs.remove(key)
            }
        }

        val restoredPrefs = context.dataStore.data.first()
        persistThemePreferenceSnapshot(
            context,
            ThemePreferenceSnapshot(
                dynamicColor = restoredPrefs[SettingsKeys.DYNAMIC_COLOR] ?: true,
                forceDark = restoredPrefs[SettingsKeys.FORCE_DARK] ?: false,
                followSystemDark = restoredPrefs[SettingsKeys.FOLLOW_SYSTEM_DARK] ?: true
            )
        )
        persistBootstrapSettingsSnapshot(context, restoredPrefs.toBootstrapSettingsSnapshot())
        persistPlaybackPreferenceSnapshot(context, restoredPrefs.toPlaybackPreferenceSnapshot())
    }

    private fun restoreAuth(
        payload: AppConfigBackup,
        sections: AppConfigBackupSections,
        warnings: MutableList<String>
    ): Int {
        var restoredCount = 0

        if (sections.neteaseAuth) {
            if (payload.neteaseAuth.hasData()) {
                val saved = neteaseRepository.saveCookies(
                    cookies = payload.neteaseAuth.cookies,
                    savedAt = payload.neteaseAuth.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
                if (!saved) {
                    warnings += context.getString(CoreCommonR.string.config_import_warning_netease_cookie)
                } else {
                    restoredCount++
                }
            } else {
                neteaseRepository.clear()
            }
        }

        if (sections.biliAuth) {
            if (payload.biliAuth.hasData()) {
                biliRepository.saveCookies(
                    cookies = payload.biliAuth.cookies,
                    savedAt = payload.biliAuth.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
                restoredCount++
            } else {
                biliRepository.clear()
            }
        }

        if (sections.youTubeAuth) {
            if (payload.youTubeAuth.hasData()) {
                youTubeRepository.saveAuth(payload.youTubeAuth.toAuthBundle())
                restoredCount++
            } else {
                youTubeRepository.clear()
            }
        }

        return restoredCount
    }

    private fun reconcileSyncWorkers(
        gitHubStorage: SecureTokenStorage,
        webDavStorage: WebDavStorage
    ) {
        if (gitHubStorage.isConfigured() && gitHubStorage.isAutoSyncEnabled()) {
            GitHubSyncWorker.schedulePeriodicSync(context)
        } else {
            GitHubSyncWorker.cancelAllSync(context)
        }

        if (webDavStorage.isConfigured() && webDavStorage.isAutoSyncEnabled()) {
            WebDavSyncWorker.schedulePeriodicSync(context)
        } else {
            WebDavSyncWorker.cancelAllSync(context)
        }
    }

}

private fun Preferences.toTypedPreferenceSnapshot(): TypedPreferenceSnapshot {
    val values = asMap()
    return TypedPreferenceSnapshot(
        booleans = SETTINGS_BOOLEAN_KEYS.mapNotNull { key ->
            (values[key] as? Boolean)?.let { key.name to it }
        }.toMap(linkedMapOf()),
        floats = SETTINGS_FLOAT_KEYS.mapNotNull { key ->
            (values[key] as? Float)?.let { key.name to it }
        }.toMap(linkedMapOf()),
        ints = SETTINGS_INT_KEYS.mapNotNull { key ->
            (values[key] as? Int)?.let { key.name to it }
        }.toMap(linkedMapOf()),
        longs = SETTINGS_LONG_KEYS.mapNotNull { key ->
            (values[key] as? Long)?.let { key.name to it }
        }.toMap(linkedMapOf()),
        strings = SETTINGS_STRING_KEYS.mapNotNull { key ->
            (values[key] as? String)?.let { key.name to it }
        }.toMap(linkedMapOf())
    )
}

private fun AppConfigBackup.syncSectionCount(sections: AppConfigBackupSections): Int {
    return listOf(
        sections.gitHubSync && gitHubSync.hasData(),
        sections.webDavSync && webDavSync.hasData(),
        sections.syncPreferences && syncPreferences.hasData()
    ).count { it }
}

private fun LanguageConfigSnapshot.toLanguageOrNull(): LanguageManager.Language? {
    return when (code.trim()) {
        LanguageManager.Language.CHINESE.code -> LanguageManager.Language.CHINESE
        LanguageManager.Language.ENGLISH.code -> LanguageManager.Language.ENGLISH
        LanguageManager.Language.SYSTEM.code -> LanguageManager.Language.SYSTEM
        else -> null
    }
}

private fun YouTubeAuthBundle.toConfigSnapshot(): YouTubeAuthConfigSnapshot {
    val normalized = normalized(savedAt = savedAt)
    return YouTubeAuthConfigSnapshot(
        cookieHeader = normalized.cookieHeader,
        cookies = normalized.cookies,
        authorization = normalized.authorization,
        xGoogAuthUser = normalized.xGoogAuthUser,
        origin = normalized.origin,
        userAgent = normalized.userAgent,
        savedAt = normalized.savedAt
    )
}

private fun YouTubeAuthConfigSnapshot.toAuthBundle(): YouTubeAuthBundle {
    return YouTubeAuthBundle(
        cookieHeader = cookieHeader,
        cookies = cookies,
        authorization = authorization,
        xGoogAuthUser = xGoogAuthUser,
        origin = origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
        userAgent = userAgent,
        savedAt = savedAt
    ).normalized(savedAt = savedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
}

private fun <K, V> List<Pair<K, V>>.toMap(destination: LinkedHashMap<K, V>): LinkedHashMap<K, V> {
    forEach { (key, value) -> destination[key] = value }
    return destination
}
