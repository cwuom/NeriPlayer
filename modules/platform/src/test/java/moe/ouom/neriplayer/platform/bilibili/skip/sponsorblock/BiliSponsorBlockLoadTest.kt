package moe.ouom.neriplayer.platform.bilibili.skip.sponsorblock

import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockSegment
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockTarget
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliTestHttp
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliTestReply
import moe.ouom.neriplayer.platform.bilibili.api.client.json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSponsorBlockLoadTest {
    private val target = BiliSponsorBlockTarget(bvid = "BV14741127BN", cid = 42L, durationMs = 100_000L)

    @Test
    fun `segments are fetched by hash prefix and filtered for the target page`() = runTest {
        val http = BiliTestHttp {
            json(
                """
                [{"videoID":"BV14741127BN","segments":[
                  {"cid":"42","category":"Sponsor","actionType":"skip","segment":[1.5,4],"UUID":"kept","videoDuration":"100.4"},
                  {"cid":"42","category":"intro","actionType":"skip","segment":[2,3],"UUID":"   "},
                  {"cid":"42","category":"intro","actionType":"skip","segment":[{"start":2},3],"UUID":"object-start"},
                  {"cid":["42"],"category":"intro","actionType":"skip","segment":[2,3],"UUID":"array-cid"},
                  {"cid":"42","category":"intro","actionType":"skip","segment":[-1,3],"UUID":"negative-start"},
                  {"cid":"42","category":"intro","actionType":"skip","segment":[1e300,3],"UUID":"huge-start"}
                ]}]
                """.trimIndent()
            )
        }

        val segments = try {
            BiliSponsorBlockRepository(http.client).loadAutoSkipSegments(target.copy(bvid = " BV14741127BN "))
        } finally {
            http.close()
        }

        assertEquals(listOf(BiliSponsorBlockSegment("kept", "sponsor", 1_500L, 4_000L)), segments)
        val request = http.requests.single()
        assertEquals("https://bsbsb.top/api/skipSegments/5759", request.url.toString())
        assertEquals("https://github.com/cwuom/NeriPlayer", request.header("Origin"))
        assertEquals("NeriPlayer-Android", request.header("X-Ext-Version"))
        assertEquals("NeriPlayer Android", request.header("User-Agent"))
    }

    @Test
    fun `invalid targets never reach the network`() = runTest {
        val http = BiliTestHttp { error("unexpected request ${it.url}") }

        try {
            val repository = BiliSponsorBlockRepository(http.client)
            assertTrue(repository.loadAutoSkipSegments(target.copy(bvid = "BV123")).isEmpty())
            assertTrue(repository.loadAutoSkipSegments(target.copy(cid = 0L)).isEmpty())
        } finally {
            http.close()
        }

        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `missing, failed or broken responses yield no segments`() = runTest {
        val replies = ArrayDeque<(Request) -> BiliTestReply>().apply {
            add { BiliTestReply("[]", code = 404) }
            add { BiliTestReply("""[{"videoID":"BV14741127BN"}]""", code = 500) }
            add { throw IOException("connection reset") }
        }
        val http = BiliTestHttp { request -> replies.removeFirst()(request) }

        try {
            val repository = BiliSponsorBlockRepository(http.client)
            repeat(3) {
                assertTrue(repository.loadAutoSkipSegments(target).isEmpty())
            }
        } finally {
            http.close()
        }

        assertEquals(3, http.requests.size)
    }

    @Test
    fun `submitted durations only veto segments when clearly different`() {
        assertTrue(isBiliSponsorBlockDurationCompatible("50", targetDurationMs = 0L))
        assertTrue(isBiliSponsorBlockDurationCompatible(null, targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("unknown", targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("NaN", targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("Infinity", targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("-5", targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("1e300", targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("0", targetDurationMs = 100_000L))
        assertTrue(isBiliSponsorBlockDurationCompatible("98", targetDurationMs = 100_000L))
        assertFalse(isBiliSponsorBlockDurationCompatible("97.9", targetDurationMs = 100_000L))
    }
}
