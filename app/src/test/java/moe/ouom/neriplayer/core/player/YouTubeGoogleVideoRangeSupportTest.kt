package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeGoogleVideoRangeSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeGoogleVideoRangeSupportTest {

    @Test
    fun shouldUseChunkedRange_matchesYoutubeGoogleVideoPlaybackUrl() {
        val url =
            "https://rr2---sn-aigzrn7k.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3965665"

        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsNonYoutubeUrl() {
        val url = "https://example.com/audio.mp3"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsLookalikeGoogleVideoHost() {
        val url =
            "https://rr2---sn.fakegooglevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3965665"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
        assertFalse(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsHlsManifestUrl() {
        val url =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1773862162/id/demo/itag/234/source/youtube/playlist/index.m3u8"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsHlsSegmentUrl() {
        val url =
            "https://rr1---sn-aigzrnze.googlevideo.com/videoplayback/id/demo/itag/234/source/youtube/playlist/index.m3u8/begin/0/len/3750/file/seg.ts"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_matchesResolvedWebRemixDirectUrl() {
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fwebm"

        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRangeForDownload_matchesResolvedDirectPlayback() {
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fwebm&clen=3965665"

        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(url))
    }

    @Test
    fun shouldUseChunkedRangeForDownload_matchesPlainPlaybackUrl() {
        val url =
            "https://rr2---sn-aigzrn7k.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3965665"

        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(url))
    }

    @Test
    fun shouldUseChunkedRangeForDownload_rejectsHlsAndNonGoogleVideo() {
        val manifestUrl =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1773862162/id/demo/itag/234/source/youtube/playlist/index.m3u8"
        val segmentUrl =
            "https://rr1---sn-aigzrnze.googlevideo.com/videoplayback/id/demo/itag/234/source/youtube/playlist/index.m3u8/begin/0/len/3750/file/seg.ts"
        val lookalikeUrl =
            "https://rr2---sn.fakegooglevideo.com/videoplayback?source=youtube&clen=3965665"
        val nonYouTubeUrl = "https://example.com/audio.m4a"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(manifestUrl))
        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(segmentUrl))
        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(lookalikeUrl))
        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(nonYouTubeUrl))
    }

    @Test
    fun supportsSeekingWithoutUrlRefresh_acceptsSigOnlyDirectUrl() {
        val url =
            "https://rr4---sn-3pm7dnes.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&sig=resolved-signature&clen=3433755"

        assertTrue(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh(url))
        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }
}
