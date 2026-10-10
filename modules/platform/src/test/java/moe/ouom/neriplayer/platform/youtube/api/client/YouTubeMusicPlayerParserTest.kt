package moe.ouom.neriplayer.platform.youtube.api.client

import java.io.IOException
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlayableAudio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeMusicPlayerParserTest {
    @Test
    fun `playable audio prefers sized formats and skips formats that still need deciphering`() {
        val audio = YouTubeMusicPlayerParser.parsePlayableAudio(
            JSONObject(
                """
                {"videoDetails": {"lengthSeconds": "200"},
                 "streamingData": {"adaptiveFormats": [
                   "not-a-format",
                   {"mimeType": "video/mp4; codecs=\"avc1\"", "url": "https://video.example/v.mp4",
                    "contentLength": "999999", "bitrate": 900000},
                   {"mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "s=SIG&sp=sig&url=https%3A%2F%2Faudio.example%2Fsigned",
                    "contentLength": "8192", "bitrate": 999999},
                   {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "url": "https://audio.example/high.m4a",
                    "bitrate": 256000},
                   {"mimeType": "audio/webm; codecs=\"opus\"",
                    "cipher": "url=https%3A%2F%2Faudio.example%2Fopus%3Fid%3D1&sp=sig",
                    "approxDurationMs": "199500", "contentLength": "4096", "bitrate": 64000}
                 ]}}
                """.trimIndent()
            )
        )

        assertEquals(
            YouTubeMusicPlayableAudio(
                url = "https://audio.example/opus?id=1",
                durationMs = 199_500L,
                mimeType = "audio/webm",
                contentLength = 4096L,
                bitrate = 64_000
            ),
            audio
        )
    }

    @Test
    fun `playable audio falls back to the video length and the highest bitrate`() {
        val audio = YouTubeMusicPlayerParser.parsePlayableAudio(
            JSONObject(
                """
                {"videoDetails": {"lengthSeconds": "200"},
                 "streamingData": {"adaptiveFormats": [
                   {"mimeType": "audio/mp4", "url": "https://audio.example/low.m4a", "bitrate": 128000},
                   {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "url": " https://audio.example/high.m4a ",
                    "bitrate": 256000}
                 ]}}
                """.trimIndent()
            )
        )

        assertEquals(
            YouTubeMusicPlayableAudio("https://audio.example/high.m4a", 200_000L, "audio/mp4", null, 256_000),
            audio
        )
    }

    @Test
    fun `playable audio is absent without streaming data or audio formats`() {
        assertNull(YouTubeMusicPlayerParser.parsePlayableAudio(JSONObject("{}")))
        assertNull(
            YouTubeMusicPlayerParser.parsePlayableAudio(
                JSONObject("""{"streamingData":{"adaptiveFormats":[{"mimeType":"video/webm","url":"https://v"}]}}""")
            )
        )

        val unknownLength = YouTubeMusicPlayerParser.parsePlayableAudio(
            JSONObject("""{"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","url":"https://a"}]}}""")
        )
        assertEquals(YouTubeMusicPlayableAudio("https://a", 0L, "audio/mp4", null, 0), unknownLength)
    }

    @Test
    fun `unplayable responses report the status reason and distinct messages`() {
        YouTubeMusicPlayerParser.requirePlayable(JSONObject("""{"playabilityStatus":{"status":"OK"}}"""))
        YouTubeMusicPlayerParser.requirePlayable(JSONObject("{}"))

        val detailed = assertThrows(IOException::class.java) {
            YouTubeMusicPlayerParser.requirePlayable(
                JSONObject(
                    """
                    {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age",
                     "messages":["Sign in to confirm your age"," This video may be inappropriate ",""]}}
                    """.trimIndent()
                )
            )
        }
        val bare = assertThrows(IOException::class.java) {
            YouTubeMusicPlayerParser.requirePlayable(JSONObject("""{"playabilityStatus":{"status":"ERROR"}}"""))
        }

        assertEquals(
            "YouTube Music player not playable (LOGIN_REQUIRED): " +
                "Sign in to confirm your age | This video may be inappropriate",
            detailed.message
        )
        assertEquals("YouTube Music player not playable (ERROR)", bare.message)
    }
}
