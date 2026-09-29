package moe.ouom.neriplayer.api.youtube.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YouTubeHlsPlaylistSelectionTest {
    @Test
    fun selectAudioPlaylist_prefersHighestBitrateHlsTrackForVeryHighQuality() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/233/sgoap/clen%3D1361514%3Bdur%3D223.143%3Bgir%3Dyes%3Bitag%3D139/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default"
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/234/sgoap/clen%3D3611036%3Bdur%3D223.074%3Bgir%3Dyes%3Bitag%3D140/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val selected = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = manifest,
            preferredQualityKey = "very_high",
            durationMs = 223_000L
        )

        assertNotNull(selected)
        assertEquals(140, selected?.audioItag)
        assertEquals(3_611_036L, selected?.contentLength)
    }

    @Test
    fun selectAudioPlaylist_prefersLowestBitrateHlsTrackForLowQuality() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/233/sgoap/clen%3D1361514%3Bdur%3D223.143%3Bgir%3Dyes%3Bitag%3D139/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default"
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/234/sgoap/clen%3D3611036%3Bdur%3D223.074%3Bgir%3Dyes%3Bitag%3D140/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val selected = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = manifest,
            preferredQualityKey = "low",
            durationMs = 223_000L
        )

        assertNotNull(selected)
        assertEquals(139, selected?.audioItag)
        assertEquals(1_361_514L, selected?.contentLength)
    }

    @Test
    fun selectAudioPlaylist_resolvesRelativeUriAgainstMasterManifestUrl() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio/itag/234/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val selected = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = manifest,
            masterManifestUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8",
            preferredQualityKey = "very_high",
            durationMs = 223_000L
        )

        assertNotNull(selected)
        assertEquals(
            "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8",
            selected?.uri
        )
    }
}
