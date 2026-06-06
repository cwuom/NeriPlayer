package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaybackResponseParserTest {

    @Test
    fun parsePlayback_marksNoPermissionWhenUrlIsJsonNull() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": null,
                "code": 404,
                "fee": 0,
                "freeTrialPrivilege": {
                  "cannotListenReason": 1
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 180_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Failure)
        assertEquals(
            NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Failure).reason
        )
    }

    @Test
    fun parsePlayback_marksPreviewWhenOnlyClipIsPlayable() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "http://m801.music.126.net/demo.mp3",
                "type": "mp3",
                "time": 30040,
                "freeTrialInfo": {
                  "fragmentType": 6
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 171_111L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Success)
        assertEquals(
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Success).notice
        )
    }

    @Test
    fun parsePlayback_marksPreviewWhenFreeTrialInfoExistsEvenForShortSong() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "http://m801.music.126.net/demo.mp3",
                "type": "mp3",
                "time": 30040,
                "freeTrialInfo": {
                  "fragmentType": 6
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 30_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Success)
        assertEquals(
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Success).notice
        )
    }

    @Test
    fun parsePlayback_marksPreviewWhenPlayableClipIsTwentySomethingSeconds() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "http://m702.music.126.net/demo.mp3",
                "type": "MP3",
                "time": 26984,
                "freeTrialInfo": {
                  "fragmentType": 1017,
                  "start": 1,
                  "end": 28
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 180_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Success)
        assertEquals(
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Success).notice
        )
    }

    @Test
    fun parseDownloadInfo_rejectsLiteralNullUrl() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "null",
                "type": "mp3"
              }]
            }
        """.trimIndent()

        assertNull(NeteasePlaybackResponseParser.parseDownloadInfo(raw))
    }

    @Test
    fun parsePlayback_treatsCode404AsRestrictedResource() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": null,
                "code": 404,
                "fee": 0
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 240_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Failure)
        assertEquals(
            NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Failure).reason
        )
    }
}
