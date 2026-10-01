package moe.ouom.neriplayer.platform.bilibili.skip.resolver

import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTargetOption

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

class BiliVideoSkipTargetResolverTest {
    @Test
    fun blankBvidDoesNotRequestVideoInformation() = runBlocking {
        val client = mock(BiliClient::class.java)

        assertTrue(resolveBiliVideoSkipTargetOptions(" ", client).isEmpty())

        verifyNoInteractions(client)
    }

    @Test
    fun videoOptionsNormalizeTargetsAndKeepValidPageOrder() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid("BV1test")).thenReturn(
            VideoBasicInfo(
                aid = 1L,
                bvid = " BV1test ",
                title = "video",
                coverUrl = "",
                desc = "",
                durationSec = 10,
                ownerMid = 1L,
                ownerName = "uploader",
                ownerFace = "",
                stats = VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
                pages = listOf(
                    VideoPage(0L, 1, "invalid", 10, 0, 0),
                    VideoPage(42L, 2, " ", -1, 0, 0),
                    VideoPage(43L, 3, " named part ", 12, 0, 0)
                )
            )
        )

        assertEquals(
            listOf(
                BiliVideoSkipTargetOption(BiliVideoSkipTarget("BV1test", 42L), "P2", 0L),
                BiliVideoSkipTargetOption(BiliVideoSkipTarget("BV1test", 43L), "named part", 12_000L)
            ),
            resolveBiliVideoSkipTargetOptions(" BV1test ", client)
        )
        verify(client).getVideoBasicInfoByBvid("BV1test")
        verifyNoMoreInteractions(client)
    }

    @Test
    fun cancellationIsReturnedToTheCaller() = runBlocking {
        val client = mock(BiliClient::class.java)
        val cancelled = CancellationException("target request cancelled")
        `when`(client.getVideoBasicInfoByBvid("BV1test")).thenThrow(cancelled)

        val result = runCatching { resolveBiliVideoSkipTargetOptions("BV1test", client) }

        assertSame(cancelled, result.exceptionOrNull())
    }
}
