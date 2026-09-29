package moe.ouom.neriplayer.core.startup.safemode

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.web.clearAllWebViewLoginState
import moe.ouom.neriplayer.data.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.data.model.settings.bootstrap.BootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.bootstrap.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.playback.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.appearance.persistThemePreferenceSnapshot

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
        clearAllWebViewLoginState(appContext)
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
