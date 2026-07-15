package moe.ouom.neriplayer.data.config

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.persistThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.toBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.toPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.sync.SyncPreferences
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.data.sync.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.core.logging.NPLogger

class ConfigFileManager(private val context: Context) {
    companion object {
        private const val TAG = "ConfigFileManager"
        private const val MAX_CONFIG_IMPORT_BYTES = 2L * 1024L * 1024L
    }

    suspend fun exportConfig(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val settingsPrefs = context.dataStore.data.first()
            val listenTogetherPreferences = ListenTogetherPreferences(context)
            val neteaseCookieRepo = NeteaseCookieRepository(context)
            val biliCookieRepo = BiliCookieRepository(context)
            val youTubeAuthRepo = YouTubeAuthRepository(context)
            val gitHubStorage = SecureTokenStorage(context)
            val payload = AppConfigBackup(
                exportedAt = System.currentTimeMillis(),
                settings = settingsPrefs.toTypedPreferenceSnapshot(),
                listenTogether = listenTogetherPreferences.snapshot(),
                language = LanguageConfigSnapshot(
                    code = LanguageManager.getCurrentLanguage(context).code
                ),
                neteaseAuth = neteaseCookieRepo.run {
                    SavedCookieConfigSnapshot(
                        cookies = getCookiesOnce(),
                        savedAt = getAuthHealthOnce().savedAt
                    )
                },
                biliAuth = biliCookieRepo.run {
                    SavedCookieConfigSnapshot(
                        cookies = getCookiesOnce(),
                        savedAt = getAuthHealthOnce().savedAt
                    )
                },
                youTubeAuth = youTubeAuthRepo.getAuthOnce().toConfigSnapshot(),
                gitHubSync = gitHubStorage.snapshot(),
                webDavSync = WebDavStorage(context).snapshot(),
                syncPreferences = SyncPreferences(context).snapshot(
                    gitHubStorage.getLegacyPlayHistoryUpdateModeName()
                )
            )

            val encoded = AppConfigBackupCodec.encode(payload)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(encoded)
            } ?: throw IllegalStateException(context.getString(R.string.error_cannot_open_output))

            val fileName = DocumentFile.fromSingleUri(context, uri)?.name
                ?.takeIf { it.isNotBlank() }
                ?: AppConfigBackupCodec.generateFileName(payload.exportedAt)
            Result.success(fileName)
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to export config file", e)
            Result.failure(e)
        }
    }

    suspend fun importConfig(uri: Uri): Result<AppConfigImportResult> = withContext(Dispatchers.IO) {
        try {
            val raw = LimitedTextReader.readUtf8(context, uri, MAX_CONFIG_IMPORT_BYTES)
            val payload = AppConfigBackupCodec.decode(raw)
            SyncCoordinator.withExclusive {
                val warnings = mutableListOf<String>()

                val sanitizedSettings = ConfigSettingsSanitizer(context).sanitize(payload.settings, warnings)
                restoreSettings(sanitizedSettings)
                AppContainer.listenTogetherPreferences.restore(payload.listenTogether)

                val currentLanguage = LanguageManager.getCurrentLanguage(context)
                val importedLanguage = payload.language.toLanguageOrNull()
                if (importedLanguage != null) {
                    LanguageManager.setLanguage(context, importedLanguage)
                }

                val restoredAuthCount = restoreAuth(payload, warnings)
                val gitHubStorage = SecureTokenStorage(context)
                val webDavStorage = WebDavStorage(context)
                val syncPreferences = SyncPreferences(context)
                syncPreferences.restore(
                    snapshot = payload.syncPreferences,
                    legacyModeName = payload.gitHubSync.playHistoryUpdateMode
                )
                gitHubStorage.restore(payload.gitHubSync)
                webDavStorage.restore(payload.webDavSync)
                gitHubStorage.markSyncMutation()
                reconcileSyncWorkers(gitHubStorage, webDavStorage)

                Result.success(
                    AppConfigImportResult(
                        restoredSettingsCount = sanitizedSettings.entryCount(),
                        restoredListenTogetherCount = payload.listenTogether.entryCount(),
                        restoredAuthCount = restoredAuthCount,
                        restoredSyncCount = payload.syncSectionCount(),
                        warnings = warnings,
                        requiresActivityRecreate = importedLanguage != null && importedLanguage != currentLanguage
                    )
                )
            }
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

    private fun restoreAuth(payload: AppConfigBackup, warnings: MutableList<String>): Int {
        var restoredCount = 0

        if (payload.neteaseAuth.hasData()) {
            val saved = AppContainer.neteaseCookieRepo.saveCookies(
                cookies = payload.neteaseAuth.cookies,
                savedAt = payload.neteaseAuth.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            if (!saved) {
                warnings += context.getString(R.string.config_import_warning_netease_cookie)
            } else {
                restoredCount++
            }
        } else {
            AppContainer.neteaseCookieRepo.clear()
        }

        if (payload.biliAuth.hasData()) {
            AppContainer.biliCookieRepo.saveCookies(
                cookies = payload.biliAuth.cookies,
                savedAt = payload.biliAuth.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            restoredCount++
        } else {
            AppContainer.biliCookieRepo.clear()
        }

        if (payload.youTubeAuth.hasData()) {
            AppContainer.youtubeAuthRepo.saveAuth(payload.youTubeAuth.toAuthBundle())
            restoredCount++
        } else {
            AppContainer.youtubeAuthRepo.clear()
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

private fun AppConfigBackup.syncSectionCount(): Int {
    return listOf(gitHubSync.hasData(), webDavSync.hasData(), syncPreferences.hasData()).count { it }
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
