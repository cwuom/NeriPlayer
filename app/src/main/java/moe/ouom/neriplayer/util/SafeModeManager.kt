package moe.ouom.neriplayer.util

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.web.clearWebViewLoginState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.backup.BackupManager
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.data.settings.BootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.persistThemePreferenceSnapshot
import java.io.File

internal object SafeModeManager {

    fun shouldEnterSafeMode(context: Context): Boolean {
        return CrashReportStore.hasPendingCrashReport(context.applicationContext)
    }

    suspend fun readPendingCrashReport(context: Context): CrashReportStore.PendingCrashReport? {
        return withContext(Dispatchers.IO) {
            CrashReportStore.readPendingCrashReport(context.applicationContext)
        }
    }

    suspend fun readFullCrashReport(reportFile: File): String? {
        return withContext(Dispatchers.IO) {
            CrashReportStore.readFullCrashReport(reportFile)
        }
    }

    suspend fun exportCrashReport(
        context: Context,
        reportFile: File,
        destination: Uri
    ) {
        withContext(Dispatchers.IO) {
            CrashReportStore.exportCrashReport(
                context = context.applicationContext,
                reportFile = reportFile,
                destination = destination
            )
        }
    }

    suspend fun exportConfigBackup(context: Context, destination: Uri): Result<String> {
        return ConfigFileManager(context.applicationContext).exportConfig(destination)
    }

    suspend fun exportPlaylistBackup(context: Context, destination: Uri): Result<String> {
        return BackupManager(context.applicationContext).exportPlaylists(destination)
    }

    fun generateConfigBackupFileName(context: Context): String {
        return ConfigFileManager(context.applicationContext).generateBackupFileName()
    }

    fun generatePlaylistBackupFileName(context: Context): String {
        return BackupManager(context.applicationContext).generateBackupFileName()
    }

    suspend fun clearAllCookiesAndLoginOptions(context: Context) {
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            NeteaseCookieRepository(appContext).clear()
            BiliCookieRepository(appContext).clear()
            YouTubeAuthRepository(appContext).clear()
        }
        clearWebViewState()
    }

    suspend fun resetAppSettings(context: Context) {
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            appContext.dataStore.edit { prefs ->
                prefs.clear()
            }
            persistThemePreferenceSnapshot(appContext, ThemePreferenceSnapshot())
            persistBootstrapSettingsSnapshot(appContext, BootstrapSettingsSnapshot())
            persistPlaybackPreferenceSnapshot(appContext, PlaybackPreferenceSnapshot())
        }
    }

    fun restoreNormalStartup(context: Context) {
        CrashReportStore.clearPendingCrashReport(context.applicationContext)
    }

    private suspend fun clearWebViewState() {
        clearWebViewLoginState()
    }
}
