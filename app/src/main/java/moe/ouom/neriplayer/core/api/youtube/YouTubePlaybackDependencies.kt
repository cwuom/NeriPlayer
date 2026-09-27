package moe.ouom.neriplayer.core.api.youtube

import android.content.Context
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import okhttp3.OkHttpClient

internal fun createPlaybackEjsSolver(
    context: Context?,
    okHttpClient: OkHttpClient
): YouTubeEjsChallengeSolver? = context?.let { YouTubeEjsChallengeSolver(it, okHttpClient) }

internal fun resolvePlaybackPoTokenProvider(
    provider: YouTubePoTokenProvider?,
    context: Context?,
    authProvider: () -> YouTubeAuthBundle
): YouTubePoTokenProvider? = provider ?: context?.let { YouTubeWebPoTokenProvider(it, authProvider) }

internal fun attachPlaybackFallbackStore(context: Context?) {
    if (context == null) return
    runCatching { NewPipeFallbackTracker.attachStore(YouTubeNewPipeFallbackStore(context)) }
}
