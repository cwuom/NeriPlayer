package moe.ouom.neriplayer.data.model.playback

sealed interface RestoredPlaybackState {
    val positionMs: Long

    data object None : RestoredPlaybackState {
        override val positionMs = 0L
    }

    data class Paused(override val positionMs: Long) : RestoredPlaybackState {
        init { require(positionMs >= 0L) }
    }

    data class ResumePending(override val positionMs: Long) : RestoredPlaybackState {
        init { require(positionMs >= 0L) }
    }

    fun withoutAutoResume(): RestoredPlaybackState = when (this) {
        is ResumePending -> Paused(positionMs)
        None, is Paused -> this
    }

    companion object {
        fun from(positionMs: Long, shouldResume: Boolean): RestoredPlaybackState =
            if (shouldResume) ResumePending(positionMs.coerceAtLeast(0L))
            else Paused(positionMs.coerceAtLeast(0L))
    }
}
