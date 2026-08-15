package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SongUrlResolutionRetryTest {

    @Test
    fun `resolution retries five times before succeeding`() = runBlocking {
        var attempts = 0
        val retryNumbers = mutableListOf<Int>()

        val result = retrySongUrlResolution(
            delayBeforeRetry = { retryNumbers += it }
        ) { attempt ->
            attempts += 1
            if (attempt == SONG_URL_RESOLUTION_RETRY_COUNT) {
                SongUrlResult.Success("https://example.com/audio.m4a")
            } else {
                SongUrlResult.Failure
            }
        }

        assertEquals(SONG_URL_RESOLUTION_RETRY_COUNT + 1, attempts)
        assertEquals((1..SONG_URL_RESOLUTION_RETRY_COUNT).toList(), retryNumbers)
        assertEquals("https://example.com/audio.m4a", (result as SongUrlResult.Success).url)
    }

    @Test
    fun `resolution does not retry a login requirement`() = runBlocking {
        var attempts = 0

        val result = retrySongUrlResolution(
            delayBeforeRetry = { error("login result must not retry") }
        ) {
            attempts += 1
            SongUrlResult.RequiresLogin
        }

        assertEquals(1, attempts)
        assertSame(SongUrlResult.RequiresLogin, result)
    }

    @Test
    fun `authoritative stream wait skips local resolution`() = runBlocking {
        var resolved = false

        val result = resolveSongUrlOrWaitForAuthoritativeStream(
            shouldWaitForAuthoritativeStream = { true }
        ) {
            resolved = true
            SongUrlResult.Failure
        }

        assertSame(SongUrlResult.WaitingForAuthoritativeStream, result)
        assertEquals(false, resolved)
    }

    @Test
    fun `authoritative stream wait is rechecked after local resolution`() = runBlocking {
        var shouldWait = false

        val result = resolveSongUrlOrWaitForAuthoritativeStream(
            shouldWaitForAuthoritativeStream = { shouldWait }
        ) {
            shouldWait = true
            SongUrlResult.Success(
                url = "https://example.com/preview.m4a",
                isPreviewClip = true,
                noticeMessage = "preview"
            )
        }

        assertSame(SongUrlResult.WaitingForAuthoritativeStream, result)
    }
}
