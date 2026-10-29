package moe.ouom.neriplayer.lanltw.playback

import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

/** All calls occur on the player's application thread. Preparation must not start playback. */
interface LanPlaybackPort {
    val preparedContentDigest: String? get() = null
    val schedulingLeadMs: Double get() = 0.0
    val preparationError: String get() = "SOURCE"
    fun watchAudioFailure(handler: (String) -> Unit) = Unit
    suspend fun resolveSharedAudioSources(track: ListenTogetherTrack): List<String> = emptyList()
    fun sharedAudioSources(track: ListenTogetherTrack, urls: List<String>) = Unit
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
