package moe.ouom.neriplayer.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import moe.ouom.neriplayer.data.model.config.AppConfigBackup
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.config.SavedCookieConfigSnapshot
import moe.ouom.neriplayer.data.model.config.SyncPreferencesConfigSnapshot
import moe.ouom.neriplayer.data.model.config.TypedPreferenceSnapshot
import moe.ouom.neriplayer.data.model.config.WebDavSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.config.YouTubeAuthConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigBackupSecurityTest {
    private val payload = AppConfigBackup(
        settings = TypedPreferenceSnapshot(strings = mapOf("audio_quality" to "lossless")),
        neteaseAuth = SavedCookieConfigSnapshot(cookies = mapOf("MUSIC_U" to "synthetic-netease")),
        biliAuth = SavedCookieConfigSnapshot(cookies = mapOf("SESSDATA" to "synthetic-bili")),
        youTubeAuth = YouTubeAuthConfigSnapshot(cookieHeader = "SID=synthetic-youtube"),
        gitHubSync = GitHubSyncConfigSnapshot(token = "synthetic-github", repoOwner = "owner", repoName = "repo"),
        webDavSync = WebDavSyncConfigSnapshot(serverUrl = "https://dav.example", password = "synthetic-webdav"),
        syncPreferences = SyncPreferencesConfigSnapshot(playHistoryUpdateMode = "EVERY_30_MINUTES")
    )

    @Test
    fun `default export omits account sections while retaining settings and shared sync preferences`() {
        val exported = AppConfigBackupCodec.encodeForExport(payload)
        val root = Json.parseToJsonElement(exported).jsonObject
        val decoded = AppConfigBackupCodec.decodeForImport(exported)

        for (section in listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")) {
            assertFalse("unexpected section: $section", section in root)
        }
        assertEquals("lossless", decoded.payload.settings.strings["audio_quality"])
        assertEquals("EVERY_30_MINUTES", decoded.payload.syncPreferences.playHistoryUpdateMode)
        assertFalse(decoded.sections.neteaseAuth)
        assertFalse(decoded.sections.biliAuth)
        assertFalse(decoded.sections.youTubeAuth)
        assertFalse(decoded.sections.gitHubSync)
        assertFalse(decoded.sections.webDavSync)
    }

    @Test
    fun `explicit account export preserves every legacy account section`() {
        val exported = AppConfigBackupCodec.encodeForExport(payload, ConfigExportOptions(includeLoginState = true))

        assertEquals(payload, AppConfigBackupCodec.decode(exported))
    }

    @Test
    fun `encrypted account export conceals credentials and uses fresh salt and iv`() {
        fun export(): String = AppConfigBackupCodec.encodeForExport(
            payload,
            ConfigExportOptions(includeLoginState = true),
            " 密码 😀 ".toCharArray()
        )
        val first = export()
        val second = export()
        val firstEnvelope = Json.parseToJsonElement(first).jsonObject
        val secondEnvelope = Json.parseToJsonElement(second).jsonObject

        assertFalse(first.contains("synthetic-"))
        assertFalse(first.contains("neteaseAuth"))
        for (field in listOf("salt", "iv", "ciphertext")) {
            assertNotEquals(firstEnvelope[field], secondEnvelope[field])
        }
        assertEquals(payload, AppConfigBackupCodec.decodeForImport(first, " 密码 😀 ".toCharArray()).payload)
    }

    @Test
    fun `node openssl envelope imports with the agreed password derivation and authenticated header`() {
        val password = "fixture password".toCharArray()

        val decoded = AppConfigBackupCodec.decodeForImport(FIXTURE, password)

        assertEquals("lossless", decoded.payload.settings.strings["audio_quality"])
        assertTrue(decoded.sections.settings)
        assertFalse(decoded.sections.neteaseAuth)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `wrong passwords and altered ciphertext have the same safe failure and consume passwords`() {
        val root = Json.parseToJsonElement(FIXTURE).jsonObject
        val altered = JsonObject(root + ("ciphertext" to JsonPrimitive(
            root.getValue("ciphertext").toString().trim('"').replaceFirst("m", "n")
        ))).toString()
        for ((raw, text) in listOf(FIXTURE to "wrong password", altered to "fixture password")) {
            val password = text.toCharArray()

            assertThrows(ConfigBackupPasswordException::class.java) {
                AppConfigBackupCodec.decodeForImport(raw, password)
            }

            assertTrue(password.all { it == '\u0000' })
        }
    }

    @Test
    fun `encrypted input requests a password only after validating its envelope`() {
        assertThrows(ConfigBackupPasswordRequiredException::class.java) {
            AppConfigBackupCodec.decodeForImport(FIXTURE)
        }
        val root = Json.parseToJsonElement(FIXTURE).jsonObject
        for ((field, value) in listOf(
            "formatVersion" to JsonPrimitive(2),
            "kdf" to JsonPrimitive("unknown"),
            "iterations" to JsonPrimitive(Int.MAX_VALUE),
            "iterations" to JsonPrimitive(1),
            "cipher" to JsonPrimitive("AES-CBC"),
            "salt" to JsonPrimitive("AA=="),
            "iv" to JsonPrimitive("AA=="),
            "ciphertext" to JsonPrimitive("AA=="),
            "ciphertext" to JsonPrimitive("not base64")
        )) {
            assertThrows("invalid field: $field", IllegalArgumentException::class.java) {
                AppConfigBackupCodec.decodeForImport(JsonObject(root + (field to value)).toString())
            }
        }
    }

    @Test
    fun `export consumes passwords even when they are rejected before encryption`() {
        for (password in listOf(charArrayOf(), CharArray(1025) { 'p' })) {
            assertThrows(IllegalArgumentException::class.java) {
                AppConfigBackupCodec.encodeForExport(payload, password = password)
            }
            assertTrue(password.all { it == '\u0000' })
        }
        val password = "fixture password".toCharArray()
        AppConfigBackupCodec.encodeForExport(payload, password = password)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `plaintext and encrypted exports enforce the plaintext import budget before encryption`() {
        val oversized = payload.copy(settings = TypedPreferenceSnapshot(strings = mapOf("large" to "x".repeat(2 * 1024 * 1024))))

        assertThrows(IllegalArgumentException::class.java) {
            AppConfigBackupCodec.encodeForExport(oversized)
        }
        val password = "fixture password".toCharArray()
        assertThrows(IllegalArgumentException::class.java) {
            AppConfigBackupCodec.encodeForExport(oversized, password = password)
        }
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `oversized plaintext and envelopes are rejected before parsing or requesting passwords`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppConfigBackupCodec.decodeForImport(" ".repeat(3 * 1024 * 1024 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfigBackupCodec.decodeForImport(" ".repeat(2 * 1024 * 1024) + "{}")
        }
    }

    @Test
    fun `invalid decrypted payload errors never expose credentials or parser causes`() {
        val secret = "synthetic-secret-never-show-in-errors"
        val malformed = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"settings":{"booleans":{"dynamic_color":"$secret"}}}"""
        val encrypted = ConfigBackupEncryption.encrypt(malformed, "fixture password".toCharArray())

        for (raw in listOf(malformed, encrypted)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AppConfigBackupCodec.decodeForImport(raw, "fixture password".toCharArray())
            }

            assertFalse(error.message.orEmpty().contains(secret))
            assertNull(error.cause)
        }
    }

    private companion object {
        // 固定向量由 Node crypto 的 OpenSSL 实现独立生成，避免只验证自身往返
        const val FIXTURE = """{"kind":"moe.ouom.neriplayer.config.encrypted","formatVersion":1,"kdf":"PBKDF2-HMAC-SHA256","iterations":600000,"cipher":"AES-256-GCM","salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"EBESExQVFhcYGRob","ciphertext":"m72RL77kxfGuHlqYa9jgR0S+yXymVQAscd3eN7HmRg00Oj2Xy0vfYZDWbxdV0kpVq3GcihX3hZvyNbyPcmOINGv3Bu+AbXON7+oEEdRdblhZyJv5lB628F1W+4/WIk/DImbunfLBho56wqHYzhM2a201zFMGz01Vx4o2"}"""
    }
}
