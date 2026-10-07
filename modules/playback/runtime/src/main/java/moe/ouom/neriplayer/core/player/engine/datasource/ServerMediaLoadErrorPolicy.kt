@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException

/** Preserve the default policy for existing sources; never retry a known permanent server error. */
internal class ServerMediaLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = SubsonicException.find(loadErrorInfo.exception)
            ?: return super.getRetryDelayMsFor(loadErrorInfo)
        if (!error.retryable) return C.TIME_UNSET
        // Cap consecutive retries at this load boundary; successful progress remains Media3's job.
        if (loadErrorInfo.errorCount > 3) return C.TIME_UNSET
        val delay = super.getRetryDelayMsFor(loadErrorInfo)
        val requestedDelay = error.retryAfterMs ?: return delay
        // Long server backoff belongs in a user-visible retry state, not an indefinite loader wait.
        if (requestedDelay > 30_000L || delay == C.TIME_UNSET) return C.TIME_UNSET
        return maxOf(delay, requestedDelay)
    }
}
