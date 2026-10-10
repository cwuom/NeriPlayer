package moe.ouom.neriplayer.core.player.download.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioHlsPlaylistTagSupportTest {
    private val playlistUrl = "https://cdn.example/hls/audio/index.m3u8"

    @Test
    fun `clear key declarations still allow segment downloads`() {
        listOf(
            "#EXT-X-KEY:METHOD=NONE",
            "#ext-x-key:URI=\"key.bin\", method=\"none\"",
            "#EXT-X-PROGRAM-DATE-TIME:2026-10-07T00:00:00Z"
        ).forEach { tag ->
            assertEquals(
                tag,
                listOf("https://cdn.example/hls/audio/seg-1.ts", "https://cdn.example/segments/seg-2.ts"),
                AudioHlsSegmentSupport.parseSegmentUrls(playlistUrl, playlist(tag))
            )
        }
    }

    @Test
    fun `encrypted or undeclared key methods are rejected`() {
        listOf(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
            "#EXT-X-KEY:URI=\"key.bin\"",
            "#EXT-X-KEY"
        ).forEach { tag ->
            val error = assertThrows(tag, IllegalArgumentException::class.java) {
                AudioHlsSegmentSupport.parseSegmentUrls(playlistUrl, playlist(tag))
            }
            assertEquals(tag, "Unsupported HLS tag: #EXT-X-KEY", error.message)
        }
    }

    @Test
    fun `media sequence is read from the first sequence tag`() {
        assertEquals(12L, AudioHlsSegmentSupport.parseMediaSequence("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:12\n#EXTINF:10,\na.ts"))
        assertEquals(
            0L,
            AudioHlsSegmentSupport.parseMediaSequence("#EXTM3U\n  #ext-x-media-sequence: 0 \n#EXT-X-MEDIA-SEQUENCE:9")
        )
    }

    @Test
    fun `missing malformed or negative media sequence is ignored`() {
        listOf(
            "#EXTM3U\n#EXTINF:10,\na.ts",
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE",
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:first",
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:-3"
        ).forEach { playlistText ->
            assertNull(playlistText, AudioHlsSegmentSupport.parseMediaSequence(playlistText))
        }
    }

    private fun playlist(tag: String): String {
        return listOf(
            "#EXTM3U",
            "#EXT-X-TARGETDURATION:10",
            tag,
            "#EXTINF:10,",
            "seg-1.ts",
            "#EXTINF:10,",
            "/segments/seg-2.ts",
            "#EXT-X-ENDLIST"
        ).joinToString("\n")
    }
}
