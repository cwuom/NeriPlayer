package moe.ouom.neriplayer.platform.netease.api.playback.parser

import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.DownloadInfo
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.FailureReason
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.PlaybackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteasePlaybackResponseFieldTest {

    @Test
    fun `download info requires a successful response with a data url`() {
        listOf(
            "not json",
            """{"code":500,"data":{"url":"https://a/b.mp3"}}""",
            """{"code":200}""",
            """{"code":200,"data":"oops"}""",
            """{"code":200,"data":{"url":null}}"""
        ).forEach { raw ->
            assertNull(raw, NeteasePlaybackResponseParser.parseDownloadInfo(raw))
        }
    }

    @Test
    fun `download info cleans type and size values`() {
        assertEquals(
            DownloadInfo("https://a/b.mp3", type = "7", contentLength = 2_048L),
            NeteasePlaybackResponseParser.parseDownloadInfo(
                """{"code":200,"data":{"url":" https://a/b.mp3 ","type":7,"size":"2048"}}"""
            )
        )
        assertEquals(
            DownloadInfo("https://a/c.flac", type = null, contentLength = null),
            NeteasePlaybackResponseParser.parseDownloadInfo(
                """{"code":200,"data":[{"url":"https://a/c.flac","type":"","size":0}]}"""
            )
        )
        assertEquals(
            DownloadInfo("https://a/d.mp3", type = null, contentLength = null),
            NeteasePlaybackResponseParser.parseDownloadInfo(
                """{"code":200,"data":[{"url":"https://a/d.mp3","type":null,"size":true}]}"""
            )
        )
        assertEquals(
            DownloadInfo("https://a/e.mp3", type = null, contentLength = null),
            NeteasePlaybackResponseParser.parseDownloadInfo(
                """{"code":200,"data":[{"url":"https://a/e.mp3","size":null}]}"""
            )
        )
    }

    @Test
    fun `playback failures distinguish login, permission and missing urls`() {
        fun reason(data: String): PlaybackResult =
            NeteasePlaybackResponseParser.parsePlayback("""{"code":200,"data":[$data]}""", originalDurationMs = 1L)

        assertEquals(PlaybackResult.RequiresLogin, NeteasePlaybackResponseParser.parsePlayback("""{"code":301}""", 1L))
        assertEquals(failure(FailureReason.UNKNOWN), NeteasePlaybackResponseParser.parsePlayback("""{"code":404}""", 1L))
        assertEquals(failure(FailureReason.UNKNOWN), NeteasePlaybackResponseParser.parsePlayback("{", 1L))
        assertEquals(
            failure(FailureReason.NO_PLAY_URL),
            NeteasePlaybackResponseParser.parsePlayback("""{"code":200,"data":[]}""", 1L)
        )
        assertEquals(failure(FailureReason.NO_PLAY_URL), reason("""{"url":null,"code":0}"""))
        assertEquals(failure(FailureReason.NO_PLAY_URL), reason("""{"url":null,"freeTrialPrivilege":{}}"""))
        assertEquals(
            failure(FailureReason.NO_PLAY_URL),
            reason("""{"url":null,"freeTrialPrivilege":{"cannotListenReason":true}}""")
        )
        assertEquals(
            failure(FailureReason.NO_PLAY_URL),
            reason("""{"url":null,"freeTrialPrivilege":{"cannotListenReason":2}}""")
        )
        assertEquals(
            failure(FailureReason.NO_PERMISSION),
            reason("""{"url":null,"freeTrialPrivilege":{"cannotListenReason":"1"}}""")
        )
        assertEquals(
            failure(FailureReason.NO_PERMISSION),
            reason("""{"url":"null","freeTrialPrivilege":{"cannotListenReason":null},"fee":8}""")
        )
    }

    @Test
    fun `playback success normalizes optional metadata`() {
        assertEquals(
            PlaybackResult.Success(
                url = "https://a/full.flac",
                type = "FLAC",
                level = "lossless",
                bitrateKbps = 1_411,
                contentLength = 30_000_000L,
                songId = 12_345L,
                durationMs = 200_000L,
                contentMd5 = "abcdef0123456789abcdef0123456789"
            ),
            NeteasePlaybackResponseParser.parsePlayback(
                """
                {"code":200,"data":{"url":"https://a/full.flac","type":"FLAC","level":"lossless",
                "bitrate":"1411","size":"30000000","id":"12345","time":200000,
                "md5":"ABCDEF0123456789ABCDEF0123456789","freeTrialInfo":null}}
                """.trimIndent(),
                originalDurationMs = 200_000L
            )
        )
        assertEquals(
            PlaybackResult.Success(url = "https://a/low.mp3", type = null, level = null, bitrateKbps = 999),
            NeteasePlaybackResponseParser.parsePlayback(
                """
                {"code":200,"data":[{"url":"https://a/low.mp3","br":0,"bitrate":0,"bitrateKbps":999000,
                "id":0,"time":-5,"size":-1,"md5":"not-a-md5","level":"null"}]}
                """.trimIndent(),
                originalDurationMs = 1L
            )
        )
    }

    @Test
    fun `playback bitrate is dropped when absent or out of range`() {
        fun bitrate(data: String): Int? = (
            NeteasePlaybackResponseParser.parsePlayback("""{"code":200,"data":[$data]}""", 1L) as PlaybackResult.Success
            ).bitrateKbps

        assertNull(bitrate("""{"url":"https://a/none.mp3"}"""))
        assertEquals(128, bitrate("""{"url":"https://a/kbps.mp3","bitrateKbps":"128"}"""))
        assertNull(bitrate("""{"url":"https://a/huge.mp3","br":3000000000000}"""))
    }

    private fun failure(reason: FailureReason) = PlaybackResult.Failure(reason)
}
