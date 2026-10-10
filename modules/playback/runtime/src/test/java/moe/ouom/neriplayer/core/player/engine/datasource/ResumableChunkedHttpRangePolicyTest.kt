@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableChunkedHttpRangePolicyTest {

    private val googleVideo =
        mockHttpUri("https://rr3---sn-abc.googlevideo.com/videoplayback?source=youtube&itag=251")
    private val neteaseFlac = mockHttpUri("https://m701.music.126.net/20260101/abc/song.flac")

    @Test
    fun `direct googlevideo and netease flac GET requests are chunked`() {
        assertTrue(defaultResumableChunkedHttpRangePolicy.shouldUse(httpGet(googleVideo)))
        assertTrue(defaultResumableChunkedHttpRangePolicy.shouldUse(httpGet(neteaseFlac)))
    }

    @Test
    fun `explicit ranges, non GET methods and other hosts are left alone`() {
        assertFalse(shouldUseResumableChunkedHttpRange(httpGet(googleVideo, headers = mapOf("range" to "bytes=0-1"))))
        assertFalse(
            shouldUseResumableChunkedHttpRange(
                DataSpec.Builder()
                    .setUri(neteaseFlac)
                    .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .build()
            )
        )
        assertFalse(shouldUseResumableChunkedHttpRange(httpGet(mockHttpUri("https://cdn.example.com/song.mp3"))))
        assertFalse(
            shouldUseResumableChunkedHttpRange(
                httpGet(mockHttpUri("https://manifest.googlevideo.com/api/manifest/hls_variant/id/1"))
            )
        )
    }
}
