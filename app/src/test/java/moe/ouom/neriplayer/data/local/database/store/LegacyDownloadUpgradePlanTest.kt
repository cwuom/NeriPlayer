package moe.ouom.neriplayer.data.local.database.store

import java.io.File
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

    @Test
    fun `user-cleared queue payload is settled without making its source removable`() {
        val result = LegacyDownloadUpgradeResult(
            tableFound = true,
            rowsSeen = 2,
            rowsCompleted = 0,
            rowsPending = 2,
            rowResults = emptyList(),
            temporaryTableCleaned = false,
            legacyProjectionTablesCleaned = false,
            rowsSuppressedByUserClear = 2
        )

        assertFalse(result.isComplete)
        assertTrue(result.isUserClearSuppressed)
        assertTrue(result.isSettled)
    }

    @Test
    fun `retryable legacy rows prevent a user-clear suppression from settling`() {
        val result = LegacyDownloadUpgradeResult(
            tableFound = true,
            rowsSeen = 3,
            rowsCompleted = 0,
            rowsPending = 3,
            rowResults = emptyList(),
            temporaryTableCleaned = false,
            legacyProjectionTablesCleaned = false,
            rowsSuppressedByUserClear = 2
        )

        assertFalse(result.isUserClearSuppressed)
        assertFalse(result.isSettled)
    }

    @Test
    fun `user-clear marker is checked before QUEUED upsert and suppression stays out of cleanup`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/store/" +
                "LegacyDownloadUpgradeCoordinator.kt"
        ).readText()
        val operationBody = source.substringAfter("private suspend fun persistLegacyOperation")
            .substringBefore("private data class LegacyCoverMaterializationResult")
        val cleanupInput = source.substringAfter("stableKeys = settledResults.asSequence()")
            .substringBefore("rowsSeen += rows.size")
        val suppressionCheckIndex = operationBody.indexOf("isLegacyQueueImportSuppressed()")
        val upsertIndex = operationBody.indexOf("DownloadExecutionRoomStore.upsert(")

        assertTrue(operationBody.contains("database.withTransaction"))
        assertTrue(suppressionCheckIndex >= 0)
        assertTrue(upsertIndex > suppressionCheckIndex)
        assertTrue(cleanupInput.contains("LegacyDownloadUpgradeRowStatus.COMPLETED"))
        assertTrue(cleanupInput.contains("LegacyDownloadUpgradeRowStatus.QUARANTINED"))
        assertFalse(cleanupInput.contains("QUEUE_IMPORT_SUPPRESSED"))
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        var attempts = 0
        while (attempts++ < 5) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: break
        }
        error("source file not found: $path")
    }
}
