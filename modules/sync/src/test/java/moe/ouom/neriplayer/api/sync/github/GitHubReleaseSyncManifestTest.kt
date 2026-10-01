package moe.ouom.neriplayer.api.sync.github

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseSyncManifestTest {

    private val gson = Gson()

    @Test
    fun `manifest is plain utf8 metadata for a raw binary asset`() {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xFF.toByte())
        val manifest = GitHubReleaseSyncManifest.create(
            assetId = 42L,
            assetName = "neriplayer-sync-v1-a1b2c3d4.bin",
            content = payload
        )

        val json = gson.toJson(manifest)

        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"assetId\":42"))
        assertTrue(json.contains("\"contentSize\":5"))
        assertFalse(json.contains("H4sI"))
        assertEquals(manifest, GitHubReleaseSyncManifest.parse(json, gson))
    }

    @Test
    fun `manifest rejects a non sha256 checksum`() {
        val invalid = """
            {"formatVersion":1,"assetId":42,"assetName":"backup.bin","contentSize":5,"contentSha256":"bad"}
        """.trimIndent()

        var threw = false
        try {
            GitHubReleaseSyncManifest.parse(invalid, gson)
        } catch (_: IllegalArgumentException) {
            threw = true
        }

        assertTrue(threw)
    }

    @Test
    fun `manifest rejects incompatible version and incomplete asset metadata`() {
        val valid = GitHubReleaseSyncManifest.create(42, "backup.bin", byteArrayOf(1))
        for (invalid in listOf(
            valid.copy(formatVersion = 2), valid.copy(assetId = 0),
            valid.copy(assetName = " "), valid.copy(contentSize = 0)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                GitHubReleaseSyncManifest.parse(gson.toJson(invalid), gson)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { GitHubReleaseSyncManifest.parse("null", gson) }
    }
}
