package moe.ouom.neriplayer.api.youtube.bootstrap

import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubePlaybackBootstrapPolicyTest {
    @Test
    fun `data sync id preserves delegated and user sessions`() {
        assertEquals("" to "", parseYouTubeDataSyncId(" "))
        assertEquals("" to "user", parseYouTubeDataSyncId("user"))
        assertEquals("delegated" to "user", parseYouTubeDataSyncId("delegated||user"))
        assertEquals("" to "delegated", parseYouTubeDataSyncId("delegated||"))
        assertEquals("delegated" to "user||tail", parseYouTubeDataSyncId("delegated||user||tail"))
    }

    @Test
    fun `player script URL resolves all supported relative forms`() {
        assertEquals("https://a.test/player.js", resolveYouTubePlayerJavaScriptUrl("https://a.test/player.js"))
        assertEquals("http://a.test/player.js", resolveYouTubePlayerJavaScriptUrl("http://a.test/player.js"))
        assertEquals("https://a.test/player.js", resolveYouTubePlayerJavaScriptUrl("//a.test/player.js"))
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/player.js", resolveYouTubePlayerJavaScriptUrl("/player.js"))
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/player.js", resolveYouTubePlayerJavaScriptUrl("player.js"))
    }
}
