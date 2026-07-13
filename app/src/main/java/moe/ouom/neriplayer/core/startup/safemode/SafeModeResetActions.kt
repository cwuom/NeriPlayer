package moe.ouom.neriplayer.core.startup.safemode

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.web.clearWebViewLoginState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.settings.BootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.persistThemePreferenceSnapshot

internal class SafeModeResetActions(
    context: Context
) {
    private val appContext = context.applicationContext

    suspend fun clearAllCookiesAndLoginOptions() {
        withContext(Dispatchers.IO) {
            NeteaseCookieRepository(appContext).clear()
            BiliCookieRepository(appContext).clear()
            YouTubeAuthRepository(appContext).clear()
        }
        clearWebViewLoginState()
    }

    suspend fun resetAppSettings() {
        withContext(Dispatchers.IO) {
            appContext.dataStore.edit { prefs ->
                prefs.clear()
            }
            persistThemePreferenceSnapshot(appContext, ThemePreferenceSnapshot())
            persistBootstrapSettingsSnapshot(appContext, BootstrapSettingsSnapshot())
            persistPlaybackPreferenceSnapshot(appContext, PlaybackPreferenceSnapshot())
        }
    }
}
