package moe.ouom.neriplayer.data.local.database.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDownloadUpgradePlanTest {
    @Test
    fun `pending and cancelled legacy rows do not require a SAF snapshot`() {
        assertFalse(
            legacyPayloadNeedsManagedRootSnapshot(
                """{"download_pending_queue":{"stable_key":"song"}}"""
            )
        )
        assertFalse(
            legacyPayloadNeedsManagedRootSnapshot(
                """{"download_cancelled_key":{"stable_key":"song"}}"""
            )
        )
        assertFalse(
            legacyPayloadNeedsManagedRootSnapshot(
                """
                {
                  "stableKey":"song",
                  "download_pending_queue":{"stable_key":"song"},
                  "name":"Song",
                  "artist":"Artist"
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `metadata rows and malformed rows remain blocked without a snapshot`() {
        assertTrue(
            legacyPayloadNeedsManagedRootSnapshot(
                """{"download_snapshot_metadata":{"audio_name":"song.mp3"}}"""
            )
        )
        assertTrue(legacyPayloadNeedsManagedRootSnapshot("{"))
    }

    @Test
    fun `mixed pending and metadata rows require a managed root snapshot`() {
        assertTrue(
            legacyPayloadNeedsManagedRootSnapshot(
                """
                {
                  "stableKey":"song",
                  "download_pending_queue":{"stable_key":"song"},
                  "download_snapshot_metadata":{"audio_name":"song.mp3"}
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `synthetic snapshot identities remain unresolved`() {
        assertTrue(isUnresolvedLegacyStableKey("legacy:catalog:row"))
        assertTrue(isUnresolvedLegacyStableKey("legacy-snapshot:root:entry"))
        assertFalse(isUnresolvedLegacyStableKey("youtube:video-1"))
    }

    @Test
    fun `snapshot entry lookup hints include exact references and names`() {
        val hints = legacyAudioLookupHints(
            org.json.JSONObject(
                """
                {
                  "download_snapshot_entries":[
                    {
                      "media_uri":"content://managed/audio/42",
                      "name":"song.mp3"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertEquals(listOf("content://managed/audio/42"), hints.references)
        assertEquals(listOf("song.mp3"), hints.names)
    }
}
