package moe.ouom.neriplayer.core.download.storage.migration.recovery

import android.content.SharedPreferences
import java.security.MessageDigest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHostTestSupport
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCleanupReceipt
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationRequest
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore.Companion.ACTIVE_REPLACEMENT_JOURNAL_KEY
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore.Companion.ACTIVE_REQUEST_KEY
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore.Companion.COPY_RECEIPT_INDEX_KEY_PREFIX
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore.Companion.COPY_RECEIPT_KEY_PREFIX
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore.Companion.PROGRESS_KEY_PREFIX
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadMigrationCheckpointStoreDurabilityTest {
    private val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
    private val store = ManagedDownloadMigrationCheckpointStore(preferences)

    @Test
    fun `request reads separate missing blank malformed and unsupported payloads`() {
        assertNull(store.readRequest())
        preferences.values[ACTIVE_REQUEST_KEY] = "   "
        assertNull(store.readRequest())

        preferences.values[ACTIVE_REQUEST_KEY] = "{broken"
        val malformed = assertThrows(ManagedDownloadMigrationException::class.java) { store.readRequest() }
        assertTrue(malformed.retryable)
        assertTrue(malformed.cause is JSONException)

        preferences.values[ACTIVE_REQUEST_KEY] = """{"version":2,"workId":"work"}"""
        val unsupported = assertThrows(ManagedDownloadMigrationException::class.java) { store.readRequest() }
        assertTrue(unsupported.retryable)
        assertNull(unsupported.cause)

        preferences.values[ACTIVE_REQUEST_KEY] = """{"version":1,"workId":"  "}"""
        assertNull(assertThrows(ManagedDownloadMigrationException::class.java) { store.readRequest() }.cause)
    }

    @Test
    fun `request decoding treats json null blank and absent optional fields as missing`() {
        preferences.values[ACTIVE_REQUEST_KEY] =
            """{"workId":" work ","fromDirectoryUri":null,"toDirectoryUri":"  ","targetLabel":" Target "}"""
        assertEquals(
            ManagedMigrationRequest(
                workId = "work",
                fromDirectoryUri = null,
                toDirectoryUri = null,
                targetLabel = "Target",
                releasePreviousPermission = false,
                minimumSourceEntryCount = 0
            ),
            store.readRequest()
        )

        preferences.values[ACTIVE_REQUEST_KEY] =
            """{"workId":"work","toDirectoryUri":" content://target ","checkpointWorkId":" cp ","autoResume":false}"""
        val request = store.readRequest()
        assertEquals("content://target", request?.toDirectoryUri)
        assertEquals("cp", request?.checkpointWorkId)
        assertEquals(false, request?.autoResume)
    }

    @Test
    fun `request ownership normalizes work ids and rejects blank owners`() {
        assertFalse(store.isRequestCurrent("  "))
        assertFalse(store.isRequestCurrent("work"))

        store.recordRequest(request("work"))

        assertTrue(store.isRequestCurrent(" WORK "))
        assertFalse(store.isRequestCurrent("other"))
        assertNull(store.clearCompletedIfCurrent(" ", listOf("work")))
        assertEquals("work", store.readRequest()?.workId)
    }

    @Test
    fun `clearing a request only removes the matching active request`() {
        assertTrue(store.clearRequest("ghost"))
        store.recordRequest(request("work"))

        assertTrue(store.clearRequest("other"))
        assertEquals("work", store.readRequest()?.workId)
        assertTrue(store.clearRequest(" WORK "))
        assertNull(store.readRequest())

        store.recordRequest(request("next"))
        assertTrue(store.clearRequest())
        assertNull(store.readRequest())
    }

    @Test
    fun `completed clear removes finished checkpoints but keeps foreign request and journal`() {
        store.recordRequest(request("keep"))
        store.recordReplacementJournal(journal("keep"))
        store.recordMinimumAudioCount("done", 3)
        store.recordProgress("done", progress())
        store.recordCopyReceipt("done", receipt())

        assertTrue(store.clearCompleted(listOf(" done ", "  ")))

        assertEquals(0, store.readMinimumAudioCount("done"))
        assertNull(store.readProgress("done"))
        assertTrue(store.readCopyReceipts("done").isEmpty())
        assertEquals("keep", store.readRequest()?.workId)
        assertEquals("keep", store.readReplacementJournal()?.workId)

        val solo = ManagedDownloadMigrationCheckpointStore(DownloadExecutionHostTestSupport.StatefulSharedPreferences())
        solo.recordMinimumAudioCount("solo", 2)
        assertTrue(solo.clearCompleted(listOf("solo")))
        assertEquals(0, solo.readMinimumAudioCount("solo"))
    }

    @Test
    fun `expected request snapshots compare nullable checkpoint work ids`() {
        store.recordRequest(request("work", checkpointWorkId = "cp-1"))
        assertFalse(
            store.recordRequestIfCurrent("work", request("work", "cp-1"), expectedRequest = request("work"))
        )
        assertTrue(
            store.recordRequestIfCurrent("work", request("work"), expectedRequest = request("work", " CP-1 "))
        )
        assertNull(store.readRequest()?.checkpointWorkId)

        assertFalse(
            store.recordRequestIfCurrent("work", request("work"), expectedRequest = request("work", "cp-2"))
        )
        assertTrue(
            store.recordRequestIfCurrent("work", request("work", "cp-3"), expectedRequest = request("work"))
        )
        assertEquals("cp-3", store.readRequest()?.checkpointWorkId)
    }

    @Test
    fun `progress reads ignore blank malformed unsupported and unknown stage checkpoints`() {
        val key = "${PROGRESS_KEY_PREFIX}work"
        listOf("  ", "{broken", """{"version":2,"stage":"COPYING"}""", """{"stage":"UNKNOWN"}""").forEach { raw ->
            preferences.values[key] = raw
            assertNull(raw, store.readProgress("work"))
        }

        preferences.values[key] =
            """{"stage":" COPYING ","totalFiles":2,"processedFiles":5,"copiedFiles":-3,"currentFileName":"  "}"""
        val progress = requireNotNull(store.readProgress("work"))
        assertEquals(ManagedDownloadStorage.MigrationStage.COPYING, progress.stage)
        assertEquals(2, progress.processedFiles)
        assertEquals(0, progress.copiedFiles)
        assertNull(progress.currentFileName)
    }

    @Test
    fun `recorded progress is normalized before it becomes durable`() {
        val durable = store.recordProgress(
            "work",
            progress(currentFileName = "  ").copy(processedFiles = 9, copiedFiles = -1, copiedBytes = -5L)
        )

        assertEquals(3, durable.processedFiles)
        assertEquals(0, durable.copiedFiles)
        assertEquals(0L, durable.copiedBytes)
        assertNull(durable.currentFileName)
        assertEquals(durable, store.readProgress("work"))
        assertNull(store.recordProgress("other", progress(currentFileName = null)).currentFileName)
        assertEquals("track.mp3", store.recordProgress("named", progress(currentFileName = " track.mp3 ")).currentFileName)
    }

    @Test
    fun `single copy receipt lookups reject blank identities and unreadable payloads`() {
        assertNull(store.readCopyReceipt("  ", SOURCE))
        assertNull(store.readCopyReceipt("work", "  "))
        assertNull(store.readCopyReceipt("work", "content://source/missing"))

        preferences.values[receiptKey("work", "content://source/blank")] = "  "
        preferences.values[receiptKey("work", "content://source/broken")] = "{broken"
        assertNull(store.readCopyReceipt("work", "content://source/blank"))
        assertNull(store.readCopyReceipt("work", "content://source/broken"))

        store.recordCopyReceipt("work", receipt())
        assertEquals(receipt(), store.readCopyReceipt(" work ", " $SOURCE "))
        editReceipt("work", SOURCE) { put("version", 2) }
        assertNull(store.readCopyReceipt("work", SOURCE))
    }

    @Test
    fun `decoded copy receipts drop unusable optional entries and non positive creation times`() {
        store.recordCopyReceipt("work", receipt())
        editReceipt("work", SOURCE) {
            put("replacementBackup", JSONObject().put("name", " ").put("reference", "r").put("mediaUri", "m"))
            put("sourceLogicalCreatedAtMs", 0L)
        }
        assertEquals(receipt().copy(sourceLogicalCreatedAtMs = null), store.readCopyReceipt("work", SOURCE))

        editReceipt("work", SOURCE) { remove("sourceLogicalCreatedAtMs") }
        assertNull(store.readCopyReceipt("work", SOURCE)?.sourceLogicalCreatedAtMs)

        listOf("name", "reference", "mediaUri").forEach { field ->
            store.recordCopyReceipt("work", receipt())
            editReceipt("work", SOURCE) { getJSONObject("targetEntry").put(field, "  ") }
            assertNull(field, store.readCopyReceipt("work", SOURCE))
        }
        editReceipt("work", SOURCE) { remove("targetEntry") }
        assertNull(store.readCopyReceipt("work", SOURCE))
    }

    @Test
    fun `copy receipt batches validate identities before writing`() {
        assertTrue(
            assertThrows(ManagedDownloadMigrationException::class.java) {
                store.recordCopyReceipts("  ", listOf(receipt()))
            }.retryable
        )
        assertEquals(0, store.recordCopyReceipts("work", emptyList()))
        assertFalse(preferences.values.containsKey(indexKey("work")))

        assertEquals(1, store.recordCopyReceipts("work", listOf(receipt(), receipt())))
        assertThrows(ManagedDownloadMigrationException::class.java) {
            store.recordCopyReceipts("work", listOf(receipt(), receipt().copy(sourceSizeBytes = 99L)))
        }
        assertEquals(listOf(receipt()), store.readCopyReceipts("work"))
    }

    @Test
    fun `copy receipts reject invalid target and backup entries`() {
        val invalidTargets = listOf(
            target().copy(name = "nested/audio.mp3"),
            target().copy(reference = " "),
            target().copy(mediaUri = " "),
            target().copy(isDirectory = true),
            target().copy(sizeBytes = -1L),
            target().copy(lastModifiedMs = -1L)
        )
        invalidTargets.forEach { invalid ->
            assertThrows(invalid.toString(), ManagedDownloadMigrationException::class.java) {
                store.recordCopyReceipt("work", receipt(targetEntry = invalid))
            }
        }
        assertThrows(ManagedDownloadMigrationException::class.java) {
            store.recordCopyReceipt("work", receipt().copy(replacementBackup = target().copy(isDirectory = true)))
        }
        assertTrue(store.readCopyReceipts("work").isEmpty())
    }

    @Test
    fun `receipt index stays authoritative only while every indexed payload is readable`() {
        preferences.values[indexKey("empty")] = "[]"
        preferences.values[receiptKey("empty", "content://source/legacy")] = encodedReceipt("content://source/legacy")
        store.recordCopyReceipt("empty", receipt("content://source/new"))
        assertEquals(listOf(suffix("content://source/new")), index("empty"))

        store.recordCopyReceipt("usable", receipt("content://source/a"))
        store.recordCopyReceipt("usable", receipt("content://source/b"))
        assertEquals(listOf("content://source/a", "content://source/b").map(::suffix).sorted(), index("usable"))

        preferences.values[indexKey("broken")] = JSONArray().put(suffix("content://source/blank")).toString()
        preferences.values[receiptKey("broken", "content://source/blank")] = "  "
        preferences.values[receiptKey("broken", "content://source/legacy")] = encodedReceipt("content://source/legacy")
        preferences.values["${COPY_RECEIPT_KEY_PREFIX}broken:not-a-digest"] = encodedReceipt("content://source/odd")
        store.recordCopyReceipt("broken", receipt("content://source/new"))
        assertEquals(
            listOf("content://source/blank", "content://source/legacy", "content://source/new").map(::suffix).sorted(),
            index("broken")
        )
    }

    @Test
    fun `invalid receipt indexes fall back to legacy receipt keys`() {
        val legacy = receipt("content://source/legacy")
        preferences.values[receiptKey("work", legacy.sourceReference)] = encodedReceipt(legacy.sourceReference)
        preferences.values[receiptKey("work", "content://source/broken")] = "{broken"

        listOf(null, "  ", "{broken", "[1]", """["not-a-digest"]""").forEach { raw ->
            preferences.values[indexKey("work")] = raw
            assertEquals(raw.toString(), listOf(legacy), store.readCopyReceipts("work"))
        }

        preferences.values[indexKey("work")] = JSONArray().put(suffix("content://source/broken")).toString()
        assertEquals(listOf(legacy), store.readCopyReceipts("work"))
        preferences.values[indexKey("work")] = "[]"
        assertTrue(store.readCopyReceipts("work").isEmpty())
    }

    @Test
    fun `clearing work removes indexed receipts and scans when the index is incomplete`() {
        store.recordCopyReceipt("complete", receipt("content://source/a"))
        preferences.values[receiptKey("complete-2", "content://source/a")] = encodedReceipt("content://source/a")
        assertTrue(store.clear("complete"))
        assertTrue(preferences.values.keys.none { it.contains("complete:") })
        assertFalse(preferences.values.containsKey(indexKey("complete")))
        assertTrue(preferences.values.containsKey(receiptKey("complete-2", "content://source/a")))

        store.recordCopyReceipt("blank", receipt("content://source/a"))
        preferences.values[receiptKey("blank", "content://source/a")] = "  "
        preferences.values[receiptKey("blank", "content://source/legacy")] = encodedReceipt("content://source/legacy")
        preferences.values[indexKey("missing")] = JSONArray().put(suffix("content://source/gone")).toString()
        preferences.values[receiptKey("missing", "content://source/legacy")] = encodedReceipt("content://source/legacy")
        preferences.values[receiptKey("unindexed", "content://source/legacy")] = encodedReceipt("content://source/legacy")
        listOf("blank", "missing", "unindexed").forEach { workId ->
            assertTrue(store.clear(workId))
            assertTrue(workId, preferences.values.keys.none { it.startsWith("$COPY_RECEIPT_KEY_PREFIX$workId:") })
        }

        preferences.values[indexKey("authoritative")] = "[]"
        preferences.values[receiptKey("authoritative", "content://source/legacy")] =
            encodedReceipt("content://source/legacy")
        assertTrue(store.clear("authoritative"))
        assertTrue(preferences.values.containsKey(receiptKey("authoritative", "content://source/legacy")))

        val before = preferences.values.toMap()
        assertTrue(store.clear("  "))
        assertEquals(before, preferences.values)
    }

    @Test
    fun `replacement journal reads reject malformed payloads and rethrow invalid journals`() {
        assertNull(store.readReplacementJournal())
        preferences.values[ACTIVE_REPLACEMENT_JOURNAL_KEY] = "  "
        assertNull(store.readReplacementJournal())

        preferences.values[ACTIVE_REPLACEMENT_JOURNAL_KEY] = "{broken"
        assertTrue(
            assertThrows(ManagedDownloadMigrationException::class.java) {
                store.readReplacementJournal()
            }.cause is JSONException
        )
        preferences.values[ACTIVE_REPLACEMENT_JOURNAL_KEY] = """{"workId":"  "}"""
        assertNull(assertThrows(ManagedDownloadMigrationException::class.java) { store.readReplacementJournal() }.cause)
    }

    @Test
    fun `replacement journal target names and cleanup receipts are validated on decode`() {
        store.recordReplacementJournal(journal("work"))
        assertEquals(journal("work"), store.readReplacementJournal())
        val valid = JSONObject(preferences.values[ACTIVE_REPLACEMENT_JOURNAL_KEY] as String)

        val invalidVariants = listOf<JSONObject.() -> Unit>(
            { put("targetNames", JSONObject().put("  ", "track.mp3")) },
            { put("targetNames", JSONObject().put(SOURCE, "nested/track.mp3")) },
            { put("cleanupReceipts", JSONArray().put(1)) },
            { getJSONArray("cleanupReceipts").getJSONObject(0).remove("targetEntry") }
        )
        invalidVariants.forEachIndexed { index, mutate ->
            preferences.values[ACTIVE_REPLACEMENT_JOURNAL_KEY] = JSONObject(valid.toString()).apply(mutate).toString()
            assertThrows("variant $index", ManagedDownloadMigrationException::class.java) {
                store.readReplacementJournal()
            }
        }

        preferences.values[ACTIVE_REPLACEMENT_JOURNAL_KEY] = JSONObject(valid.toString()).apply {
            remove("targetNames")
            getJSONArray("cleanupReceipts").getJSONObject(0).put("sourceLogicalCreatedAtMs", 0L)
        }.toString()
        val decoded = requireNotNull(store.readReplacementJournal())
        assertTrue(decoded.targetNamesByReference.isEmpty())
        assertNull(decoded.cleanupReceipts.single().sourceLogicalCreatedAtMs)
    }

    @Test
    fun `preference read failures degrade to missing checkpoints`() {
        val failing = mock(SharedPreferences::class.java)
        `when`(failing.getString(anyString(), any())).thenThrow(IllegalStateException("disk"))
        `when`(failing.getInt(anyString(), anyInt())).thenThrow(IllegalStateException("disk"))
        val failingStore = ManagedDownloadMigrationCheckpointStore(failing)

        assertNull(failingStore.readRequest())
        assertNull(failingStore.readReplacementJournal())
        assertNull(failingStore.readProgress("work"))
        assertNull(failingStore.readCopyReceipt("work", SOURCE))
        assertTrue(failingStore.readCopyReceipts("work").isEmpty())
        assertTrue(failingStore.readTargetNames("work").isEmpty())
        assertEquals(0, failingStore.readMinimumAudioCount("work"))
    }

    private fun request(workId: String, checkpointWorkId: String? = null): ManagedMigrationRequest {
        return ManagedMigrationRequest(
            workId = workId,
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0,
            checkpointWorkId = checkpointWorkId
        )
    }

    private fun progress(currentFileName: String? = "track.mp3"): ManagedDownloadStorage.MigrationProgress {
        return ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 3,
            processedFiles = 1,
            copiedFiles = 1,
            copiedBytes = 10L,
            totalBytes = 30L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0,
            currentFileName = currentFileName
        )
    }

    private fun target(): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = "audio.mp3",
            reference = "content://target/audio",
            mediaUri = "content://target/audio",
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 9L
        )
    }

    private fun receipt(
        sourceReference: String = SOURCE,
        targetEntry: ManagedDownloadStorage.StoredEntry = target()
    ): ManagedMigrationCopyReceipt {
        return ManagedMigrationCopyReceipt(
            sourceReference = sourceReference,
            sourceName = "audio.mp3",
            sourceSubdirectory = null,
            sourceSizeBytes = 12L,
            sourceLastModifiedMs = 8L,
            targetEntry = targetEntry,
            sourceDigest = "a".repeat(64),
            verifiedTargetDigest = "a".repeat(64),
            createdNew = true,
            sourceAuthoritative = true,
            sourceLogicalCreatedAtMs = 1_234L
        )
    }

    private fun journal(workId: String): ManagedMigrationReplacementJournal {
        return ManagedMigrationReplacementJournal(
            workId = workId,
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
            replacements = emptyList(),
            targetNamesByReference = mapOf(SOURCE to "track.mp3"),
            cleanupReceipts = listOf(
                ManagedMigrationCleanupReceipt(
                    sourceReference = SOURCE,
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    targetEntry = target(),
                    targetDigest = "b".repeat(64),
                    sourceLogicalCreatedAtMs = 55L
                )
            )
        )
    }

    private fun encodedReceipt(sourceReference: String): String {
        val scratch = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        ManagedDownloadMigrationCheckpointStore(scratch).recordCopyReceipt("scratch", receipt(sourceReference))
        return scratch.values.getValue(receiptKey("scratch", sourceReference)) as String
    }

    private fun editReceipt(workId: String, sourceReference: String, edit: JSONObject.() -> Unit) {
        val key = receiptKey(workId, sourceReference)
        preferences.values[key] = JSONObject(preferences.values.getValue(key) as String).apply(edit).toString()
    }

    private fun index(workId: String): List<String> {
        val array = JSONArray(preferences.values.getValue(indexKey(workId)) as String)
        return (0 until array.length()).map(array::getString)
    }

    private fun indexKey(workId: String): String = "$COPY_RECEIPT_INDEX_KEY_PREFIX$workId"

    private fun receiptKey(workId: String, sourceReference: String): String {
        return "$COPY_RECEIPT_KEY_PREFIX$workId:${suffix(sourceReference)}"
    }

    private fun suffix(sourceReference: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(sourceReference.trim().toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val SOURCE = "content://source/audio"
    }
}
