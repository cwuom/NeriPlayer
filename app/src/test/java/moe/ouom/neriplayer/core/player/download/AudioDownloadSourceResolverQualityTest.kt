package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioDownloadSourceResolverQualityTest {
    @Test
    fun `netease download tries lower quality before declaring source unavailable`() = runBlocking {
        val requested = mutableListOf<String>()
        val source = AudioDownloadManager.ResolvedDownloadSource(url = "https://example.com/song.mp3")

        val resolved = AudioDownloadSourceResolver.resolveNeteaseWithLookups(
            songId = 42L,
            preferredQuality = "lossless",
            eapiLookup = { _, quality ->
                requested += "eapi:$quality"
                if (quality == "standard") AudioDownloadSourceResolver.NeteaseDownloadLookup.Resolved(source)
                else AudioDownloadSourceResolver.NeteaseDownloadLookup.ExplicitlyUnavailable
            },
            weapiLookup = { _, bitrate ->
                requested += "weapi:$bitrate"
                AudioDownloadSourceResolver.NeteaseDownloadLookup.Missing
            }
        )

        assertEquals(source, resolved)
        assertEquals(
            listOf(
                "eapi:lossless", "weapi:1411200",
                "eapi:exhigh", "weapi:320000",
                "eapi:standard"
            ),
            requested
        )
    }

    @Test
    fun `netease download does not repeat qualities below standard`() = runBlocking {
        val requested = mutableListOf<String>()
        val resolved = AudioDownloadSourceResolver.resolveNeteaseWithLookups(
            songId = 42L,
            preferredQuality = "standard",
            eapiLookup = { _, quality ->
                requested += quality
                AudioDownloadSourceResolver.NeteaseDownloadLookup.Missing
            },
            weapiLookup = { _, _ -> AudioDownloadSourceResolver.NeteaseDownloadLookup.Missing }
        )
        assertEquals(null, resolved)
        assertEquals(listOf("standard"), requested)
    }

    @Test
    fun `netease download remains unavailable when every quality is denied`() {
        assertThrows(DownloadSourceUnavailableException::class.java) {
            runBlocking {
                AudioDownloadSourceResolver.resolveNeteaseWithLookups(
                    songId = 42L,
                    preferredQuality = "exhigh",
                    eapiLookup = { _, _ -> AudioDownloadSourceResolver.NeteaseDownloadLookup.ExplicitlyUnavailable },
                    weapiLookup = { _, _ -> AudioDownloadSourceResolver.NeteaseDownloadLookup.Missing }
                )
            }
        }
    }
}
