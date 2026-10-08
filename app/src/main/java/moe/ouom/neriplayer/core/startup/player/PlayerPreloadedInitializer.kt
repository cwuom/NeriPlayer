package moe.ouom.neriplayer.core.startup.player

import android.app.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.preloadRestoredStateSnapshot
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshot

/**
 * 播放偏好和上次播放快照都在 IO 上预读，主线程只做内存态初始化；
 * 裸 PlayerManager.initialize() 会在调用线程同步读取设置与 Room，主线程调用会造成 ANR
 */
internal class PlayerPreloadedInitializer(
    private val app: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readPreferences: suspend (Application) -> PlaybackPreferenceSnapshot = { application ->
        readPlaybackPreferenceSnapshot(application)
    },
    private val preloadState: suspend (Application, PlaybackPreferenceSnapshot) -> RestoredPlayerStateSnapshot? =
        { application, preferences ->
            preloadRestoredStateSnapshot(
                app = application,
                keepLastPlaybackProgressEnabled = preferences.keepLastPlaybackProgress,
                keepPlaybackModeStateEnabled = preferences.keepPlaybackModeState
            )
        },
    private val initializePlayer: (Application, PlaybackPreferenceSnapshot, RestoredPlayerStateSnapshot?) -> Unit =
        { application, preferences, restoredState ->
            PlayerManager.initializePreloaded(
                app = application,
                startupPlaybackPreferences = preferences,
                restoredStateSnapshot = restoredState
            )
        }
) {
    suspend fun initialize(beforeInitialize: suspend () -> Unit = {}): PlaybackPreferenceSnapshot {
        val preferences = withContext(ioDispatcher) { readPreferences(app) }
        val restoredState = withContext(ioDispatcher) { preloadState(app, preferences) }
        beforeInitialize()
        initializePlayer(app, preferences, restoredState)
        return preferences
    }
}
