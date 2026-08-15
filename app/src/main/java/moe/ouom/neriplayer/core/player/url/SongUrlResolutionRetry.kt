package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.player.model.SongUrlResult

internal const val SONG_URL_RESOLUTION_RETRY_COUNT = 5
private const val SONG_URL_RESOLUTION_RETRY_DELAY_MS = 250L

internal suspend fun retrySongUrlResolution(
    retryCount: Int = SONG_URL_RESOLUTION_RETRY_COUNT,
    delayBeforeRetry: suspend (retryNumber: Int) -> Unit = { retryNumber ->
        delay(SONG_URL_RESOLUTION_RETRY_DELAY_MS * retryNumber)
    },
    resolveAttempt: suspend (attempt: Int) -> SongUrlResult
): SongUrlResult {
    require(retryCount >= 0) { "retryCount must not be negative" }

    var result = resolveAttempt(0)
    repeat(retryCount) { retryIndex ->
        if (result !is SongUrlResult.Failure) return result
        val retryNumber = retryIndex + 1
        delayBeforeRetry(retryNumber)
        result = resolveAttempt(retryNumber)
    }
    return result
}

internal suspend fun resolveSongUrlOrWaitForAuthoritativeStream(
    shouldWaitForAuthoritativeStream: () -> Boolean,
    resolve: suspend () -> SongUrlResult
): SongUrlResult {
    if (shouldWaitForAuthoritativeStream()) {
        return SongUrlResult.WaitingForAuthoritativeStream
    }

    val result = resolve()
    return if (shouldWaitForAuthoritativeStream()) {
        SongUrlResult.WaitingForAuthoritativeStream
    } else {
        result
    }
}
