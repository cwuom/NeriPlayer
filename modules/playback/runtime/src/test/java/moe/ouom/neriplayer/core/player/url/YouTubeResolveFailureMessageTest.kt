package moe.ouom.neriplayer.core.player.url

import java.io.IOException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePoTokenUnavailableException
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePoTokenUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeResolveFailureMessageTest {

    private val requests = mutableListOf<Pair<Int, String>>()

    private fun message(error: Throwable): String =
        youTubeResolveFailureMessage(error) { resId, arg ->
            requests += resId to arg
            "$resId:$arg"
        }

    @Test
    fun `empty po token shows the dedicated po token error with its short code`() {
        message(YouTubePoTokenUnavailableException(YouTubePoTokenUnavailableReason.EMPTY))

        assertEquals(
            listOf(CoreCommonR.string.player_youtube_po_token_unavailable_empty to "empty"),
            requests
        )
    }

    @Test
    fun `timed out po token shows the dedicated po token error with its short code`() {
        message(YouTubePoTokenUnavailableException(YouTubePoTokenUnavailableReason.TIMEOUT))

        assertEquals(
            listOf(CoreCommonR.string.player_youtube_po_token_unavailable_timeout to "timeout"),
            requests
        )
    }

    @Test
    fun `other resolve failures keep the generic playback url error`() {
        message(IOException("YouTube player unavailable via WEB_REMIX"))

        assertEquals(
            listOf(
                CoreCommonR.string.player_playback_url_error_detail to
                    "YouTube player unavailable via WEB_REMIX"
            ),
            requests
        )
    }
}
