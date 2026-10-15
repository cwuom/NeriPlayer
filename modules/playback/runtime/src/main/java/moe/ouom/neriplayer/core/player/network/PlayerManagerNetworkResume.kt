package moe.ouom.neriplayer.core.player.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.policy.network.PLAYBACK_NETWORK_RESUME_WINDOW_MS
import moe.ouom.neriplayer.core.player.policy.network.PlaybackNetworkResumeRequest
import moe.ouom.neriplayer.core.player.policy.network.shouldHoldPlaybackForNetwork
import moe.ouom.neriplayer.core.player.policy.network.shouldResumePlaybackAfterNetworkRestored
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "NERI-NetworkResume"
private const val OFFLINE_CACHE_URL_PREFIX = "http://offline.cache/"

/** 网络回调到达后再等一下，DNS 和路由就绪前立刻重连多半还会失败 */
private const val NETWORK_SETTLE_DELAY_MS = 2_000L

internal fun mediaUrlRequiresNetwork(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    if (url.startsWith(OFFLINE_CACHE_URL_PREFIX)) return false
    return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}

/** 被系统按 uid 拦网时 activeNetwork 也会返回 null，和真正断网一样处理 */
internal fun isPlaybackNetworkUsable(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
    return runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
}

/**
 * 断网导致的失败不再暂停了事或连跳整个队列：停在原位，网络回来后在窗口内自动续播。
 * 只在等待期间注册网络回调，平时不常驻
 */
internal object PlaybackNetworkResumeOwner {
    private val lock = Any()
    private var request: PlaybackNetworkResumeRequest? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var expiryJob: Job? = null
    private var settleJob: Job? = null

    fun arm(context: Context, pending: PlaybackNetworkResumeRequest, onRestored: () -> Unit) {
        synchronized(lock) {
            request = pending
            registerLocked(context, onRestored)
            expiryJob?.cancel()
            expiryJob = PlayerManager.mainScope.launch {
                delay(PLAYBACK_NETWORK_RESUME_WINDOW_MS.milliseconds)
                disarm("window_expired")
            }
        }
        NPLogger.w(TAG, "holding playback until network returns: reason=${pending.reason}, song=${pending.songKey}")
    }

    fun pending(): PlaybackNetworkResumeRequest? = synchronized(lock) { request }

    fun disarm(reason: String) {
        val context: Context
        val registered: ConnectivityManager.NetworkCallback
        synchronized(lock) {
            if (request == null && callback == null) return
            request = null
            expiryJob?.cancel()
            expiryJob = null
            settleJob?.cancel()
            settleJob = null
            registered = callback ?: return
            callback = null
            context = PlayerManager.application
        }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(registered)
        }
        NPLogger.d(TAG, "network resume disarmed: reason=$reason")
    }

    private fun registerLocked(context: Context, onRestored: () -> Unit) {
        if (callback != null) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleResume(context, onRestored)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    scheduleResume(context, onRestored)
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!blocked) scheduleResume(context, onRestored)
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { callback = networkCallback }
            .onFailure { error -> NPLogger.w(TAG, "register network callback failed", error) }
    }

    private fun scheduleResume(context: Context, onRestored: () -> Unit) {
        synchronized(lock) {
            if (request == null || settleJob?.isActive == true) return
            settleJob = PlayerManager.mainScope.launch {
                delay(NETWORK_SETTLE_DELAY_MS.milliseconds)
                if (!isPlaybackNetworkUsable(context)) return@launch
                onRestored()
            }
        }
    }
}

/**
 * 断网时接管本该暂停或跳曲的失败分支；返回 true 表示已经停在原位并开始等网络
 */
internal fun PlayerManager.holdPlaybackForNetworkIfOffline(reason: String): Boolean {
    val song = _currentSongFlow.value ?: return false
    val requiresNetwork = !isLocalSong(song) && mediaUrlRequiresNetwork(_currentMediaUrl.value)
    if (!shouldHoldPlaybackForNetwork(resumePlaybackRequested, isPlaybackNetworkUsable(application), requiresNetwork)) {
        return false
    }
    pauseImpl(
        forcePersist = true,
        commandSource = PlaybackCommandSource.LOCAL_SAFETY,
        allowFadeOut = false,
        debugReason = "network_hold:$reason",
        flushPlayerOutput = true
    )
    PlaybackNetworkResumeOwner.arm(
        context = application,
        pending = PlaybackNetworkResumeRequest(
            songKey = song.stableKey(),
            requestToken = playbackRequestToken,
            armedAtElapsedMs = SystemClock.elapsedRealtime(),
            reason = reason
        ),
        onRestored = ::resumePlaybackAfterNetworkRestored
    )
    postPlayerEvent(PlayerEvent.ShowNotice(getLocalizedString(CoreCommonR.string.player_playback_waiting_for_network)))
    return true
}

private fun PlayerManager.resumePlaybackAfterNetworkRestored() {
    val pending = PlaybackNetworkResumeOwner.pending() ?: return
    val shouldResume = shouldResumePlaybackAfterNetworkRestored(
        request = pending,
        nowElapsedMs = SystemClock.elapsedRealtime(),
        currentSongKey = _currentSongFlow.value?.stableKey(),
        currentRequestToken = playbackRequestToken,
        playbackRequested = resumePlaybackRequested
    )
    PlaybackNetworkResumeOwner.disarm(if (shouldResume) "network_restored" else "stale_request")
    if (!shouldResume) return
    NPLogger.i(TAG, "network restored, resuming held playback: reason=${pending.reason}")
    play(PlaybackCommandSource.LOCAL)
}
