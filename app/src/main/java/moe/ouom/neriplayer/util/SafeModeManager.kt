package moe.ouom.neriplayer.util

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.settings.BootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.persistThemePreferenceSnapshot
import java.io.File
import kotlin.coroutines.resume

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
        withContext(Dispatchers.Main.immediate) {
            val cookieManager = CookieManager.getInstance()
            suspendCancellableCoroutine<Unit> { continuation ->
                cookieManager.removeAllCookies {
                    cookieManager.removeSessionCookies {
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}
