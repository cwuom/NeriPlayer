package moe.ouom.neriplayer.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AppConfigBackupCodecTest {
    @Test
    fun `download directory is retained when a persisted provider is temporarily unavailable`() {
        assertFalse(shouldClearImportedDownloadDirectory(PersistedTreeAccess.Accessible))
        assertFalse(shouldClearImportedDownloadDirectory(PersistedTreeAccess.PermissionLost))
        assertFalse(shouldClearImportedDownloadDirectory(PersistedTreeAccess.ProviderFailure))
        assertTrue(shouldWarnImportedDownloadDirectory(PersistedTreeAccess.PermissionLost))
        assertTrue(shouldWarnImportedDownloadDirectory(PersistedTreeAccess.ProviderFailure))
    }

    @Test
    fun `download directory is cleared only without a grant or after confirmed missing`() {
        assertTrue(shouldClearImportedDownloadDirectory(PersistedTreeAccess.NoPersistedPermission))
        assertTrue(shouldClearImportedDownloadDirectory(PersistedTreeAccess.Missing))
    }

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
            ),
            syncPreferences = SyncPreferencesConfigSnapshot(
                playHistoryUpdateMode = "EVERY_30_MINUTES"
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

    @Test
    fun `decode rejects unrelated json without config marker`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AppConfigBackupCodec.decode("""{"hello":"world"}""")
        }

        assertTrue(error.message.orEmpty().contains("NeriPlayer config"))
    }

    @Test
    fun `decode rejects config marker without restorable sections`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AppConfigBackupCodec.decode(
                """
                {
                  "kind": "moe.ouom.neriplayer.config",
                  "formatVersion": 1,
                  "exportedAt": 1717171717
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("no restorable content"))
    }

    @Test
    fun `decode for import reports raw top level sections`() {
        val decoded = AppConfigBackupCodec.decodeForImport(
            """
            {
              "kind": "moe.ouom.neriplayer.config",
              "formatVersion": 1,
              "settings": {
                "booleans": {
                  "dynamic_color": false
                }
              }
            }
            """.trimIndent()
        )

        assertTrue(decoded.sections.settings)
        assertFalse(decoded.sections.listenTogether)
        assertFalse(decoded.sections.neteaseAuth)
        assertEquals(false, decoded.payload.settings.booleans["dynamic_color"])
    }

    @Test
    fun `missing auto sync fields default to enabled when decoding older backups`() {
        val payload = AppConfigBackup(
            formatVersion = 1,
            exportedAt = 1_717_171_717L,
            listenTogether = ListenTogetherConfigSnapshot(
                workerBaseUrl = "https://worker.example",
                workerBaseUrlInput = "worker.example",
                userUuid = "uuid-1",
                nickname = "Neri",
                allowMemberControl = false,
                autoPauseOnMemberChange = false,
                shareAudioLinks = false
            ),
            neteaseAuth = SavedCookieConfigSnapshot(cookies = linkedMapOf("MUSIC_U" to "cookie")),
            biliAuth = SavedCookieConfigSnapshot(cookies = linkedMapOf("SESSDATA" to "cookie")),
            youTubeAuth = YouTubeAuthConfigSnapshot(cookieHeader = "SID=1")
        )

        val legacyJson = AppConfigBackupCodec.encode(payload.copy(
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
        ))
            .replace(",\"autoSyncEnabled\":true,\"playHistoryUpdateMode\":\"BATCHED\"", "")
            .replace(",\"autoSyncEnabled\":true}", "}")

        val decoded = AppConfigBackupCodec.decode(legacyJson)

        assertTrue(decoded.gitHubSync.autoSyncEnabled)
        assertTrue(decoded.webDavSync.autoSyncEnabled)
    }
}
