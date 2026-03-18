package moe.ouom.neriplayer.core.api.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YouTubeMusicPlaybackRepositoryTest {

    @Test
    fun parsePlayableAudio_usesApproxDurationMsWhenPresent() {
        val root = JSONObject(
            """
            {
              "videoDetails": {
                "lengthSeconds": "124"
              },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-1",
                    "bitrate": 128000,
                    "audioSampleRate": "44100",
                    "contentLength": "2003029",
                    "approxDurationMs": "123715"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertNotNull(playableAudio)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?id=audio-1", playableAudio?.url)
        assertEquals(123_715L, playableAudio?.durationMs)
        assertEquals("audio/mp4", playableAudio?.mimeType)
        assertEquals(2_003_029L, playableAudio?.contentLength)
    }

    @Test
    fun parsePlayableAudio_fallsBackToVideoDetailsDurationWhenApproxDurationMissing() {
        val root = JSONObject(
            """
            {
              "videoDetails": {
                "lengthSeconds": "321"
              },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-2",
                    "bitrate": 160000,
                    "audioSampleRate": "48000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertNotNull(playableAudio)
        assertEquals(321_000L, playableAudio?.durationMs)
        assertEquals("audio/webm", playableAudio?.mimeType)
    }

    @Test
    fun parsePlayableAudio_resolvesUnsignedSignatureCipherUrl() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-3&sp=sig&sig=test-signature",
                    "bitrate": 96000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "65432"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-3&sig=test-signature",
            playableAudio?.url
        )
        assertEquals(65_432L, playableAudio?.durationMs)
    }
}
