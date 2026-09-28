package moe.ouom.neriplayer.core.player.audio.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PreferredQualityKeys
import moe.ouom.neriplayer.core.player.model.forSource

internal interface PlaybackQualityPort {
    fun currentAudioSource(): PlaybackAudioSource?
    suspend fun persistPreferredQuality(source: PlaybackAudioSource, key: String)
    suspend fun refreshCurrentSong(source: PlaybackAudioSource, reason: String)
}

internal class PlaybackQualityOwner(
    private var scope: CoroutineScope,
    private val port: PlaybackQualityPort
) {
    private val mutablePreferredKeys = MutableStateFlow(PreferredQualityKeys())
    val preferredKeys: StateFlow<PreferredQualityKeys> = mutablePreferredKeys
    private val refreshJobs = mutableMapOf<PlaybackAudioSource, Job>()

    val neteasePreferredQuality: String get() = mutablePreferredKeys.value.netease
    val youtubePreferredQuality: String get() = mutablePreferredKeys.value.youtube
    val biliPreferredQuality: String get() = mutablePreferredKeys.value.bili

    fun rebindScope(scope: CoroutineScope) {
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        this.scope = scope
    }

    fun setPreferredQuality(source: PlaybackAudioSource, key: String) {
        val current = mutablePreferredKeys.value
        mutablePreferredKeys.value = when (source) {
            PlaybackAudioSource.NETEASE -> current.copy(netease = key)
            PlaybackAudioSource.YOUTUBE_MUSIC -> current.copy(youtube = key)
            PlaybackAudioSource.BILIBILI -> current.copy(bili = key)
            PlaybackAudioSource.LOCAL -> current
        }
    }

    fun changeCurrentPlaybackQuality(optionKey: String) {
        val key = optionKey.trim().lowercase()
        if (key.isBlank()) return
        val source = port.currentAudioSource() ?: return
        if (key == mutablePreferredKeys.value.forSource(source)) return
        scope.launch { port.persistPreferredQuality(source, key) }
    }

    fun scheduleRefresh(source: PlaybackAudioSource, reason: String) {
        if (source == PlaybackAudioSource.LOCAL) return
        refreshJobs.remove(source)?.cancel()
        refreshJobs[source] = scope.launch { port.refreshCurrentSong(source, reason) }
    }
}
