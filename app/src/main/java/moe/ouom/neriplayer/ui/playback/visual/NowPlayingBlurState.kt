package moe.ouom.neriplayer.ui.playback.visual

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal class NowPlayingBlurState {
    private var latestSongKey: String? = null
    private var latestCoverUrl: String? = null
    var stableCoverUrl by mutableStateOf<String?>(null)
        private set
    var stableBlurStrength by mutableStateOf<Float?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set

    fun observeRequest(songKey: String?, coverUrl: String?) {
        latestSongKey = songKey
        latestCoverUrl = coverUrl
    }

    fun clear() {
        stableCoverUrl = null
        stableBlurStrength = null
        loadFailed = false
    }

    fun onImageSuccess(
        latestRequestKey: String?,
        expectedRequestKey: String?,
        coverUrl: String?,
        blurStrength: Float
    ) {
        if (latestRequestKey != expectedRequestKey) return
        stableCoverUrl = coverUrl
        stableBlurStrength = blurStrength
        loadFailed = false
    }

    fun onImageError(latestRequestKey: String?, expectedRequestKey: String?) {
        loadFailed = resolveNowPlayingBlurLoadFailure(
            latestRequestKey,
            expectedRequestKey,
            stableCoverUrl,
            loadFailed
        )
    }

    suspend fun reconcileRetention(request: NowPlayingBlurRetentionRequest) {
        if (!request.blurAvailable || !request.blurEnabled) {
            clear()
            return
        }
        loadFailed = false
        if (request.songKey != null || !request.coverUrl.isNullOrBlank()) return
        delay(PLAYBACK_VISUAL_COVER_GRACE_MS.milliseconds)
        if (shouldClearNowPlayingBlurCover(
                currentSongKey = latestSongKey,
                requestedCoverUrl = latestCoverUrl,
                clearDelayElapsed = true
            )
        ) {
            clear()
        }
    }
}

internal data class NowPlayingBlurRetentionRequest(
    val blurAvailable: Boolean,
    val blurEnabled: Boolean,
    val songKey: String?,
    val coverUrl: String?
)
