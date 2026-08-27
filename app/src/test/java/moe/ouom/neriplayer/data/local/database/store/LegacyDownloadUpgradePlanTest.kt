package moe.ouom.neriplayer.data.local.database.store

import org.json.JSONObject
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

    @Test
    fun `stale SAF audio references contribute decoded audio file names only`() {
        val payload = JSONObject().apply {
            put(
                "mediaUri",
                "content://com.android.externalstorage.documents/tree/primary%3AOld/" +
                    "document/primary%3AOld%2Fnetease%20-%20artist%20-%20song.mp3"
            )
            put("audioReference", "content://managed/audio/42")
        }

        val hints = legacyAudioLookupHints(payload)

        assertTrue("netease - artist - song.mp3" in hints.names)
        assertFalse("42" in hints.names)
    }
}
