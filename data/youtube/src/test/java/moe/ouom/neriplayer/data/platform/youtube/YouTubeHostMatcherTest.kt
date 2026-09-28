package moe.ouom.neriplayer.data.platform.youtube

import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeHostMatcherTest {

    @Test
    fun isYouTubePageHost_acceptsExactAndSubdomainMatchesOnly() {
        assertTrue(isYouTubePageHost("youtube.com"))
        assertTrue(isYouTubePageHost("music.youtube.com"))
        assertTrue(isYouTubePageHost("www.youtube.com"))
        assertTrue(isYouTubePageHost("youtu.be"))
        assertFalse(isYouTubePageHost("notyoutube.com"))
        assertFalse(isYouTubePageHost("evilyoutube.com"))
    }

    @Test
    fun isYouTubeGoogleVideoHost_acceptsExactAndSubdomainMatchesOnly() {
        assertTrue(isYouTubeGoogleVideoHost("googlevideo.com"))
        assertTrue(isYouTubeGoogleVideoHost("rr1---sn.googlevideo.com"))
        assertFalse(isYouTubeGoogleVideoHost("fakegooglevideo.com"))
        assertFalse(isYouTubeGoogleVideoHost("googlevideo.com.evil.example"))
    }

    @Test
    fun normalizeYouTubeOriginValue_reducesPageRefererToOrigin() {
        assertEquals(
            YOUTUBE_MUSIC_ORIGIN,
            normalizeYouTubeOriginValue(
                candidate = "https://music.youtube.com/watch?v=fbvvS8e1KgI",
                fallbackOrigin = YOUTUBE_WEB_ORIGIN
            )
        )
    }

    @Test
    fun normalizeYouTubeOriginValue_usesFallbackWhenCandidateIsInvalid() {
        assertEquals(
            YOUTUBE_MUSIC_ORIGIN,
            normalizeYouTubeOriginValue(
                candidate = "not-a-valid-url",
                fallbackOrigin = YOUTUBE_MUSIC_ORIGIN
            )
        )
    }
}
