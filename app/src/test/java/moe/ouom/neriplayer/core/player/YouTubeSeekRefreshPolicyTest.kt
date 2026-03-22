package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSeekRefreshPolicyTest {

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsTrueForYoutubeDirectStream() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")
        val url =
            "https://rr1---sn-aigzrn7k.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3965665"

        assertTrue(YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, url))
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsFalseForYoutubeManifestStream() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")
        val url =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/" +
                "expire/1773862162/id/demo/itag/234/source/youtube/playlist/index.m3u8"

        assertFalse(YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, url))
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsFalseForYoutubeSegmentStream() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")
        val url =
            "https://rr3---sn-aigl6ney.googlevideo.com/videoplayback/" +
                "playlist/index.m3u8/begin/0/len/3750/file/seg.ts"

        assertFalse(YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, url))
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsFalseForResolvedWebRemixDirectStream() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3586688&n=resolved-n&sig=resolved-signature"

        assertFalse(YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, url))
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsFalseForNonYoutubeSong() {
        val song = createSong(mediaUri = null)
        val url =
            "https://rr1---sn-aigzrn7k.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3965665"

        assertFalse(YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(song, url))
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsFalseForOfflineCacheUrl() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")

        assertFalse(
            YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                song,
                "http://offline.cache/ytmusic-fbvvS8e1KgI-very_high"
            )
        )
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsFalseForLocalFile() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")

        assertFalse(
            YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                song,
                "file:///storage/emulated/0/Music/demo.m4a"
            )
        )
    }

    @Test
    fun shouldRefreshUrlBeforeSeek_returnsTrueForUnknownRemoteUrl() {
        val song = createSong(mediaUri = "https://music.youtube.com/watch?v=fbvvS8e1KgI")

        assertTrue(
            YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                song,
                "https://music.youtube.com/api/stats?foo=bar"
            )
        )
    }

    private fun createSong(mediaUri: String?): SongItem {
        return SongItem(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 223_041L,
            coverUrl = null,
            mediaUri = mediaUri
        )
    }
}
