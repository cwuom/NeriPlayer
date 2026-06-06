package moe.ouom.neriplayer.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigBackupCodecTest {
    @Test
    fun `codec round trip keeps config payload stable`() {
        val payload = AppConfigBackup(
            formatVersion = 1,
            exportedAt = 1_717_171_717L,
            settings = TypedPreferenceSnapshot(
                booleans = linkedMapOf("dynamic_color" to false),
                floats = linkedMapOf("lyric_font_scale" to 1.15f),
                ints = linkedMapOf("playback_loudness_gain_mb" to 120),
                longs = linkedMapOf("cloud_music_lyric_default_offset_ms" to -1000L),
                strings = linkedMapOf("audio_quality" to "lossless")
            ),
            listenTogether = ListenTogetherConfigSnapshot(
                workerBaseUrl = "https://worker.example",
                workerBaseUrlInput = "worker.example",
                userUuid = "uuid-1",
                nickname = "Neri",
                allowMemberControl = false,
                autoPauseOnMemberChange = false,
                shareAudioLinks = false
            ),
            language = LanguageConfigSnapshot(code = "en"),
            neteaseAuth = SavedCookieConfigSnapshot(
                cookies = linkedMapOf("MUSIC_U" to "cookie"),
                savedAt = 123L
            ),
            biliAuth = SavedCookieConfigSnapshot(
                cookies = linkedMapOf("SESSDATA" to "cookie"),
                savedAt = 456L
            ),
            youTubeAuth = YouTubeAuthConfigSnapshot(
                cookieHeader = "SID=1",
                cookies = linkedMapOf("SID" to "1"),
                authorization = "Bearer token",
                xGoogAuthUser = "0",
                origin = "https://music.youtube.com",
                userAgent = "ua",
                savedAt = 789L
            ),
            gitHubSync = GitHubSyncConfigSnapshot(
                token = "ghp_token",
                repoOwner = "owner",
                repoName = "repo",
                autoSyncEnabled = true,
                playHistoryUpdateMode = "BATCHED",
                dataSaverMode = false
            ),
            webDavSync = WebDavSyncConfigSnapshot(
                serverUrl = "https://dav.example",
                basePath = "backup",
                username = "user",
                password = "pass",
                autoSyncEnabled = true
            )
        )

        val encoded = AppConfigBackupCodec.encode(payload)
        val decoded = AppConfigBackupCodec.decode(encoded)

        assertEquals(payload, decoded)
    }

    @Test
    fun `generated filename uses config prefix and json suffix`() {
        val fileName = AppConfigBackupCodec.generateFileName(0L)

        assertTrue(fileName.startsWith("neriplayer_config_"))
        assertTrue(fileName.endsWith(".json"))
    }
}
