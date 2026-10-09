package moe.ouom.neriplayer.core.download.storage.facade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupResult
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupSkipReason
import moe.ouom.neriplayer.core.download.storage.operation.content.clearTreeDirectoryCache
import moe.ouom.neriplayer.core.download.storage.operation.content.invalidateSnapshotCache
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProbeResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.download.storage.StorageConfidence
import moe.ouom.neriplayer.data.model.download.storage.StorageMutationResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadStorageFacadeDefaultRootTest {
    private val storage = ManagedDownloadStorage
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = ManagedDownloadRootResolver.defaultRootDirectory(context).apply { mkdirs() }
    }

    /** Context-free invalidation: a context would schedule Room snapshot jobs that cannot open outside the app process. */
    @After
    fun tearDown() {
        storage.clearTreeDirectoryCache()
        storage.invalidateSnapshotCache()
    }

    @Test
    fun `document ids are known only when they equal the tree root id`() {
        assertTrue(storage.isKnownManagedDownloadDocumentId(" primary:Music ", "primary:Music"))
        assertFalse(storage.isKnownManagedDownloadDocumentId("primary:Music/Song.mp3", "primary:Music"))
        assertFalse(storage.isKnownManagedDownloadDocumentId(" ", "primary:Music"))
        assertFalse(storage.isKnownManagedDownloadDocumentId("primary:Music", null))
        assertFalse(storage.isKnownManagedDownloadDocumentId("primary:Music", "  "))
    }

    @Test
    fun `transient pending metadata depends on finalization and artifact state`() {
        assertTrue(storage.isKnownTransientPendingMetadata(DownloadedAudioMetadata(artifactState = " downloading ")))
        assertFalse(
            storage.isKnownTransientPendingMetadata(
                DownloadedAudioMetadata(artifactState = "DOWNLOADING", downloadFinalized = true)
            )
        )
        assertFalse(storage.isKnownTransientPendingMetadata(DownloadedAudioMetadata(artifactState = "COMPLETED")))
        assertFalse(storage.isKnownTransientPendingMetadata(DownloadedAudioMetadata()))
    }

    @Test
    fun `cached metadata is reused only for an unchanged entry`() {
        val metadata = DownloadedAudioMetadata(stableKey = "1|Album|")
        val current = entry("Song.mp3", size = 10L, lastModified = 5L)

        assertTrue(storage.canReuseCachedDownloadedMetadata(current, current, metadata))
        assertFalse(storage.canReuseCachedDownloadedMetadata(current, current, null))
        assertFalse(storage.canReuseCachedDownloadedMetadata(null, current, metadata))
        assertFalse(storage.canReuseCachedDownloadedMetadata(current.copy(reference = "/other"), current, metadata))
        assertFalse(storage.canReuseCachedDownloadedMetadata(current.copy(sizeBytes = 11L), current, metadata))
        assertFalse(
            storage.canReuseCachedDownloadedMetadata(
                current.copy(lastModifiedMs = 0L), current.copy(lastModifiedMs = 0L), metadata
            )
        )
        assertFalse(storage.canReuseCachedDownloadedMetadata(current.copy(lastModifiedMs = 6L), current, metadata))
    }

    @Test
    fun `unreadable metadata marks the matching pending artifacts`() {
        val pendingAudio = entry("Song.mp3.npdl_pending.op-1.pending")
        val pendingMetadata = entry("Song.mp3.npmeta.pending.json")
        val otherPending = entry("Other.mp3.npdl_pending.op-2.pending")
        val brokenMetadata = entry("Song.mp3.npmeta.json")
        val pending = listOf(pendingAudio, pendingMetadata, otherPending)
        val unreadable = setOf(brokenMetadata.reference)

        assertEquals(
            setOf(pendingAudio.reference, pendingMetadata.reference),
            storage.resolveUnreadablePendingArtifactReferences(pending, listOf(brokenMetadata), unreadable)
        )
        assertTrue(storage.resolveUnreadablePendingArtifactReferences(emptyList(), listOf(brokenMetadata), unreadable).isEmpty())
        assertTrue(storage.resolveUnreadablePendingArtifactReferences(pending, emptyList(), unreadable).isEmpty())
        assertTrue(storage.resolveUnreadablePendingArtifactReferences(pending, listOf(brokenMetadata), emptySet()).isEmpty())
        assertTrue(
            storage.resolveUnreadablePendingArtifactReferences(pending, listOf(entry("notes.txt")), setOf("/library/notes.txt"))
                .isEmpty()
        )
    }

    @Test
    fun `pending metadata rename rejects unsafe names and malformed payloads`() {
        val rewritten = storage.rewritePendingMetadataAudioFileName("""{"stableKey":"1"}""", "Song.flac")
        assertEquals("Song.flac", JSONObject(rewritten!!).getString("audioFileName"))
        assertEquals("1", JSONObject(rewritten).getString("stableKey"))

        listOf("", " Song.flac", ".", "..", "a/b.flac", "a\\b.flac").forEach { unsafe ->
            assertNull(unsafe, storage.rewritePendingMetadataAudioFileName("{}", unsafe))
        }
        assertNull(storage.rewritePendingMetadataAudioFileName("{broken", "Song.flac"))
    }

    @Test
    fun `terminal cleanup needs external signals only for permission or scope failures`() {
        val completed = ManagedTemporaryWriteCleanupResult.Completed(
            deletedCount = 1,
            missingCount = 0,
            retainedActiveCount = 0,
            failures = listOf(
                StorageMutationResult.PermissionLost,
                StorageMutationResult.OutOfScope,
                StorageMutationResult.ProviderFailure(IllegalStateException())
            )
        )
        assertEquals(2, storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(completed, 5))

        fun skipped(confidence: StorageConfidence) = ManagedTemporaryWriteCleanupResult.Skipped(
            ManagedTemporaryWriteCleanupSkipReason.IncompleteDirectory(confidence)
        )
        assertEquals(3, storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(skipped(StorageConfidence.PermissionLost), 3))
        assertEquals(1, storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(skipped(StorageConfidence.OutOfScope), 0))
        assertEquals(0, storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(skipped(StorageConfidence.Complete), 3))
        assertEquals(0, storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(skipped(StorageConfidence.Missing), 3))
        assertEquals(
            0,
            storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(
                skipped(StorageConfidence.ProviderFailure(IllegalStateException())), 3
            )
        )
        assertEquals(
            0,
            storage.terminalTemporaryWriteCleanupExternalSignalRequiredCount(
                ManagedTemporaryWriteCleanupResult.Skipped(ManagedTemporaryWriteCleanupSkipReason.TargetParentMismatch), 3
            )
        )
    }

    @Test
    fun `default root is reported accessible without a configured tree`() = runTest {
        assertEquals(ManagedDownloadRootProbeResult.Accessible, storage.probeStorageRoot(context))
        assertTrue(storage.isStorageRootResolvable(context))
    }

    @Test
    fun `pending metadata is written to the temporary root and read back`() = runTest {
        val json = """{"stableKey":"1|Album|","name":"Song","operationId":"op-1"}"""
        val pendingAudio = StoredEntry("Song.mp3.npdl_pending.op-1.pending", "/x", "/x", null, 0L, 0L)

        assertTrue(storage.writePendingAudioMetadata(context, "Song.mp3", json, operationId = "op-1"))
        val written = File(rootDir, ".tmp/Song.mp3.npmeta.pending.json")
        assertEquals(json, written.readText())

        val read = storage.readDownloadedMetadataFromRoot(
            context, pendingAudio, directoryUri = null,
            preferPendingMetadata = true, useDefaultRootWhenDirectoryUriMissing = true
        )
        assertEquals("1|Album|", read?.stableKey)
        assertEquals("Song", read?.name)

        assertFalse(storage.deletePendingAudioMetadata(context, "  "))
        assertTrue(written.exists())
    }

    @Test
    fun `formal metadata is read from the default root when no source directory is given`() = runTest {
        val audioFile = File(rootDir, "Song.mp3").apply { writeText("audio") }
        File(rootDir, "Song.mp3.npmeta.json").writeText("""{"stableKey":"1|Album|","name":"Song"}""")
        val audio = StoredEntry(audioFile.name, audioFile.absolutePath, audioFile.absolutePath, audioFile.absolutePath, 5L, 1L)

        val read = storage.readDownloadedMetadataFromRoot(
            context, audio, directoryUri = null, useDefaultRootWhenDirectoryUriMissing = true
        )
        assertEquals("Song", read?.name)
        val preferPending = storage.readDownloadedMetadataFromRoot(
            context, audio, directoryUri = null, preferPendingMetadata = true, useDefaultRootWhenDirectoryUriMissing = true
        )
        assertEquals("1|Album|", preferPending?.stableKey)
        assertEquals("Song", storage.readDownloadedMetadataFromRoot(context, audio, directoryUri = null)?.name)
        assertNull(
            storage.readDownloadedMetadataFromRoot(
                context, audio.copy(name = "Other.mp3"), directoryUri = null, useDefaultRootWhenDirectoryUriMissing = true
            )
        )
    }

    @Test
    fun `readable content requires a non directory entry with bytes`() = runTest {
        val audio = File(rootDir, "Song.mp3").apply { writeText("audio") }
        val empty = File(rootDir, "Empty.mp3").apply { writeText("") }

        assertTrue(storage.hasReadableContent(context, fileEntry(audio)))
        assertTrue(storage.hasReadableContent(context, fileEntry(audio).copy(sizeBytes = 0L)))
        assertFalse(storage.hasReadableContent(context, fileEntry(empty)))
        assertFalse(storage.hasReadableContent(context, fileEntry(audio).copy(isDirectory = true)))
        assertFalse(storage.hasReadableContent(context, fileEntry(File(rootDir, "Missing.mp3")).copy(sizeBytes = 3L)))
    }

    private fun entry(name: String, size: Long = 0L, lastModified: Long = 0L): StoredEntry {
        val path = "/library/$name"
        return StoredEntry(name, path, path, path, size, lastModified)
    }

    private fun fileEntry(file: File): StoredEntry {
        return StoredEntry(file.name, file.absolutePath, file.absolutePath, file.absolutePath, file.length(), file.lastModified())
    }
}
