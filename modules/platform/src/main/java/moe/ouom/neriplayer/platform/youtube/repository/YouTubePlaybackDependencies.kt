package moe.ouom.neriplayer.platform.youtube.repository

import android.content.Context
import moe.ouom.neriplayer.platform.youtube.api.challenge.NewPipeFallbackTracker
import moe.ouom.neriplayer.platform.youtube.api.challenge.YouTubeEjsChallengeSolver
import moe.ouom.neriplayer.platform.youtube.api.fallback.YouTubeNewPipeFallbackStore
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.api.potoken.YouTubePoTokenProvider
import moe.ouom.neriplayer.platform.youtube.api.potoken.YouTubeWebPoTokenProvider
import moe.ouom.neriplayer.platform.youtube.auth.web.YouTubeWebSessionAdapter
import okhttp3.OkHttpClient

internal fun createPlaybackEjsSolver(
    context: Context?,
    okHttpClient: OkHttpClient
): YouTubeEjsChallengeSolver? = context?.let { YouTubeEjsChallengeSolver(it, okHttpClient) }

internal fun resolvePlaybackPoTokenProvider(
    provider: YouTubePoTokenProvider?,
    context: Context?,
    authProvider: () -> YouTubeAuthBundle
): YouTubePoTokenProvider? = provider ?: context?.let { YouTubeWebPoTokenProvider(it, authProvider, YouTubeWebSessionAdapter) }

internal fun attachPlaybackFallbackStore(context: Context?) {
    if (context == null) return
    runCatching { NewPipeFallbackTracker.attachStore(YouTubeNewPipeFallbackStore(context)) }
}
