package moe.ouom.neriplayer.data.youtube.repository

import android.content.Context
import moe.ouom.neriplayer.api.youtube.challenge.NewPipeFallbackTracker
import moe.ouom.neriplayer.api.youtube.challenge.YouTubeEjsChallengeSolver
import moe.ouom.neriplayer.api.youtube.fallback.YouTubeNewPipeFallbackStore
import moe.ouom.neriplayer.api.youtube.model.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.api.youtube.potoken.YouTubePoTokenProvider
import moe.ouom.neriplayer.api.youtube.potoken.YouTubeWebPoTokenProvider
import moe.ouom.neriplayer.data.youtube.auth.web.YouTubeWebSessionAdapter
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
