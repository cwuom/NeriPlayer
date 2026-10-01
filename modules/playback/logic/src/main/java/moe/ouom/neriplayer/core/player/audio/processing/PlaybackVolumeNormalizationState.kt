package moe.ouom.neriplayer.core.player.audio.processing

import java.util.concurrent.atomic.AtomicReference

data class PlaybackVolumeNormalizationSnapshot(
    val enabled: Boolean,
    val generation: Long
)

object PlaybackVolumeNormalizationState {
    private val ref = AtomicReference(
        PlaybackVolumeNormalizationSnapshot(enabled = false, generation = 0L)
    )

    fun updateEnabled(enabled: Boolean) {
        while (true) {
            val current = ref.get()
            if (current.enabled == enabled) return
            val next = PlaybackVolumeNormalizationSnapshot(
                enabled = enabled,
                generation = current.generation + 1L
            )
            if (ref.compareAndSet(current, next)) return
        }
    }

    fun resetForNewTrack() {
        while (true) {
            val current = ref.get()
            val next = current.copy(generation = current.generation + 1L)
            if (ref.compareAndSet(current, next)) return
        }
    }

    fun current(): PlaybackVolumeNormalizationSnapshot = ref.get()
}
