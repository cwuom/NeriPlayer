package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class PlaybackMediaItemFactoryTest {
    private val context = mock(Context::class.java)

    @Test
    fun `PlayerManager source predicates retain media owner classification`() {
        assertTrue(PlayerManager.isYouTubeMusicTrack(song(mediaUri = "https://youtu.be/video")))
        assertTrue(PlayerManager.isBiliTrack(song(album = "Bilibili|123|video")))
        assertFalse(PlayerManager.isBiliTrack(song()))
    }

    @Test
    fun `cache key selects source namespace and only reads its quality`() {
        val qualityReads = mutableListOf<String>()
        val youtube = song(channelId = ListenTogetherChannels.YOUTUBE_MUSIC, audioId = "video")
        val bili = song(channelId = ListenTogetherChannels.BILIBILI, audioId = "aid", subAudioId = "cid")
        val netease = song(id = 321L)
        val local = song(localFilePath = "/storage/emulated/0/song.flac")

        assertEquals(
            "ytmusic-video-high-stable-m4a",
            key(youtube, qualityReads, youtubeQualityOverride = "high")
        )
        assertTrue(qualityReads.isEmpty())
        assertEquals("bili-aid-cid-lossless", key(bili, qualityReads))
        assertEquals(listOf("bili"), qualityReads)
        assertEquals("netease-321-exhigh-fallback-v1", key(netease, qualityReads))
        assertEquals(listOf("bili", "netease"), qualityReads)
        assertTrue(key(local, qualityReads).startsWith("local-"))
        assertEquals(listOf("bili", "netease"), qualityReads)
    }

    @Test
    fun `youtube and bili metadata fallback keep stable cache identities`() {
        val qualityReads = mutableListOf<String>()
        assertEquals(
            "ytmusic-video-very_high",
            key(song(mediaUri = "https://music.youtube.com/watch?v=video"), qualityReads)
        )
        assertEquals(
            "bili-8-123-lossless",
            key(song(id = 8L, album = "Bilibili|123|video"), qualityReads)
        )
        assertEquals("bili-8-lossless", key(song(id = 8L, album = "Bilibili"), qualityReads))
        assertEquals(listOf("youtube", "bili", "bili"), qualityReads)
    }

    @Test
    fun `quality normalization and cache namespaces stay separate`() {
        assertEquals("netease-9-exhigh", PlaybackMediaItemFactory.neteaseCacheKey(9L, "  ", false))
        assertEquals(
            "netease-9-exhigh-fallback-v1",
            PlaybackMediaItemFactory.neteaseCacheKey(9L, " ExHiGh ", true)
        )
        assertEquals("netease-preview-v1-9-exhigh", PlaybackMediaItemFactory.neteasePreviewCacheKey(9L, ""))
        assertEquals("ytmusic-video-high", PlaybackMediaItemFactory.youtubeCacheKey("video", "high", false))
    }

    @Test
    fun `media item strips transport fragment and keeps cache key only for remote media`() {
        val remoteUrl = "https://cdn.example.org/audio.m4a"
        val remoteWithQuality = "$remoteUrl#neriplayer-ltw-quality=netease:high"
        val localUrl = "content://provider.example/document/audio"
        val remoteUri = mock(Uri::class.java)
        val localUri = mock(Uri::class.java)
        `when`(remoteUri.path).thenReturn("/audio.m4a")
        `when`(localUri.path).thenReturn("/document/audio")
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(remoteUrl) }.thenReturn(remoteUri)
            uris.`when`<Uri> { Uri.parse(localUrl) }.thenReturn(localUri)

            var cacheKeyReads = 0
            val remoteItem = PlaybackMediaItemFactory.mediaItem(
                song(mediaUri = remoteUrl), remoteWithQuality, "remote-key", "audio/mp4"
            ) { cacheKeyReads++; "safe-key" }
            val localItem = PlaybackMediaItemFactory.mediaItem(
                song(mediaUri = localUrl), localUrl, "local-key", null
            ) { cacheKeyReads++; "unneeded-key" }

            assertEquals(remoteUri, remoteItem.localConfiguration?.uri)
            assertEquals("safe-key", remoteItem.localConfiguration?.customCacheKey)
            assertEquals("audio/mp4", remoteItem.localConfiguration?.mimeType)
            assertEquals(localUri, localItem.localConfiguration?.uri)
            assertEquals(null, localItem.localConfiguration?.customCacheKey)
            assertEquals(1, cacheKeyReads)
            assertFalse(remoteItem.mediaId.isBlank())
        }
    }

    @Test
    fun `all local media URL forms bypass custom cache key`() {
        var cacheKeyReads = 0
        val urls = listOf(
            "file:///music/audio.m4a",
            "content://provider.example/audio",
            "android.resource://app/raw/audio",
            "/music/audio.m4a"
        )
        val parsed = urls.associateWith { mock(Uri::class.java) }
        mockStatic(Uri::class.java).use { uris ->
            parsed.forEach { (url, uri) -> uris.`when`<Uri> { Uri.parse(url) }.thenReturn(uri) }

            urls.forEach { url ->
                val item = PlaybackMediaItemFactory.mediaItem(
                    song(), url, "local-key", null
                ) { cacheKeyReads++; "should-not-be-used" }
                assertEquals(null, item.localConfiguration?.customCacheKey)
            }
        }
        assertEquals(0, cacheKeyReads)
    }

    @Test
    fun `FLAC item keeps remote cache key and declared mime type`() {
        val url = "https://cdn.example.org/audio.flac"
        val uri = mock(Uri::class.java)
        `when`(uri.path).thenReturn("/audio.flac")
        `when`(uri.host).thenReturn("cdn.example.org")
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(url) }.thenReturn(uri)

            val item = PlaybackMediaItemFactory.mediaItem(
                song(mediaUri = url), url, "flac-key", "audio/flac"
            ) { "flac-key" }

            assertEquals("flac-key", item.localConfiguration?.customCacheKey)
            assertEquals("audio/flac", item.localConfiguration?.mimeType)

            `when`(uri.host).thenReturn(null)
            val itemWithoutOptionalMetadata = PlaybackMediaItemFactory.mediaItem(
                song(mediaUri = url), url, "flac-key", null
            ) { null }
            assertEquals(null, itemWithoutOptionalMetadata.localConfiguration?.mimeType)
            assertEquals(null, itemWithoutOptionalMetadata.localConfiguration?.customCacheKey)
        }
    }

    private fun key(
        song: SongItem,
        qualityReads: MutableList<String>,
        youtubeQualityOverride: String? = null
    ): String = PlaybackMediaItemFactory.cacheKey(
        song = song,
        context = context,
        youtubeQualityOverride = youtubeQualityOverride,
        youtubePreferM4a = youtubeQualityOverride != null,
        neteaseFallbackEnabled = { true },
        youtubeQuality = { qualityReads += "youtube"; "very_high" },
        biliQuality = { qualityReads += "bili"; "lossless" },
        neteaseQuality = { qualityReads += "netease"; "exhigh" }
    )

    private fun song(
        id: Long = 1L,
        album: String = "album",
        mediaUri: String? = null,
        localFilePath: String? = null,
        channelId: String? = null,
        audioId: String? = null,
        subAudioId: String? = null
    ) = SongItem(
        id = id,
        name = "song",
        artist = "artist",
        album = album,
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId
    )
}
