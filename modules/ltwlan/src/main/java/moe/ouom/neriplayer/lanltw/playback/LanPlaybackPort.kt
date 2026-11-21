package moe.ouom.neriplayer.lanltw.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.lan.LanStreamResource
import moe.ouom.neriplayer.lanltw.media.LanMediaServer

data class LanPreparedSource(val track: ListenTogetherTrack, val streamResource: LanStreamResource,
    val presentation: LanSharedPresentation? = null)

/** All calls occur on the player's application thread. Preparation must not start playback. */
interface LanPlaybackPort {
    val preparedMediaId: String? get() = null
    val schedulingLeadMs: Double get() = 0.0
    val preparationError: String get() = "SOURCE"
    fun watchTransferProgress(handler: (Long, Long) -> Unit) = Unit
    fun watchAudioFailure(handler: (String) -> Unit) = Unit
    fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) = Unit
    suspend fun prepareSharedPresentation(track: ListenTogetherTrack): LanSharedPresentation? = null
    fun sharedPresentationUpdates(track: ListenTogetherTrack, mediaId: String): Flow<LanSharedPresentation> = flow {
        prepareSharedPresentation(track)?.let { emit(it) }
    }
    suspend fun prepareSharedSource(track: ListenTogetherTrack, refresh: Boolean = false): LanPreparedSource
    fun hostMediaServer(server: LanMediaServer) = Unit
    fun mediaEndpoint(controlUrl: String, port: Int, token: String) = Unit
    fun sharedStreamResource(track: ListenTogetherTrack, resource: LanStreamResource?) = Unit
    fun currentTrack(): ListenTogetherTrack?
    fun positionSeconds(): Double
    fun isPlaying(): Boolean
    fun supported(): Boolean
    suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double): Boolean
    fun ready(track: ListenTogetherTrack): Boolean
    fun play()
    fun pause()
    fun seek(positionSeconds: Double)
    suspend fun setMediaVolume(value: Float)
    fun mediaVolume(): Float = 1f
    fun watchMediaVolume(changed: (Float) -> Unit) = Unit
    fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit)
    fun step(direction: Int) = Unit
    fun end()
}
