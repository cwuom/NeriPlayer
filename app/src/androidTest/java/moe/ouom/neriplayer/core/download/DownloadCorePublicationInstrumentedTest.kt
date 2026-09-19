package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.artifact.DownloadCorePublicationCoordinator
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter
import moe.ouom.neriplayer.core.download.storage.ROOT_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.operation.content.promoteFileTargetWithoutReplacement
import moe.ouom.neriplayer.core.download.storage.operation.content.publicationFileIdentity
import moe.ouom.neriplayer.core.download.storage.operation.content.recordAudioPublicationTarget
import moe.ouom.neriplayer.core.download.storage.operation.content.promotePendingAudio
import moe.ouom.neriplayer.core.download.storage.operation.content.sealAudioPublicationReceipt
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadCorePublicationInstrumentedTest {
    @Test
    fun privateCoreRemainsPendingUntilMetadataIsFinalized() = runBlocking {
        withStorage(false) { assertCoreRemainsPending() }
    }

    @Test
    fun safCoreRemainsPendingUntilMetadataIsFinalized() = runBlocking {
        withStorage(true) { assertCoreRemainsPending() }
    }

    @Test
    fun privateCommitReentryReusesTheSameOperationPendingBytes() = runBlocking {
        withStorage(false) { assertCommitReentryReusesPending() }
    }

    @Test
    fun safCommitReentryReusesTheSameOperationPendingBytes() = runBlocking {
        withStorage(true) { assertCommitReentryReusesPending() }
    }

    @Test
    fun privateColdCacheReentryReusesTheSameOperationPendingBytes() = runBlocking {
        withStorage(false) { assertCommitReentryReusesPending(coldCache = true) }
    }

    @Test
    fun safColdCacheReentryReusesTheSameOperationPendingBytes() = runBlocking {
        withStorage(true) { assertCommitReentryReusesPending(coldCache = true) }
    }

    @Test
    fun privateDifferentOwnerKeepsItsOwnPendingIdentity() = runBlocking {
        withStorage(false) { assertDifferentOwnerIsPreserved() }
    }

    @Test
    fun safDifferentOwnerKeepsItsOwnPendingIdentity() = runBlocking {
        withStorage(true) { assertDifferentOwnerIsPreserved() }
    }

    @Test
    fun privateCommitPreservesAnUnownedSameNameFile() = runBlocking {
        withStorage(false) { assertUnownedFileIsPreserved() }
    }

    @Test
    fun safCommitPreservesAnUnownedSameNameFile() = runBlocking {
        withStorage(true) { assertUnownedFileIsPreserved() }
    }

    @Test
    fun privateCommitDoesNotReuseDifferentBytesFromTheSameOperation() = runBlocking {
        withStorage(false) { assertDifferentBytesArePreserved() }
    }

    @Test
    fun safCommitDoesNotReuseDifferentBytesFromTheSameOperation() = runBlocking {
        withStorage(true) { assertDifferentBytesArePreserved() }
    }

    @Test
    fun privatePendingTagsAreReadBackBeforeFinalPublication() = runBlocking {
        withStorage(false) { assertTaggedPublication() }
    }

    @Test
    fun safPendingTagsAreReadBackBeforeFinalPublication() = runBlocking {
        withStorage(true) { assertTaggedPublication() }
    }

    @Test
    fun privateExistingFormalCoreReusesItsOperationAndFinishesTags() = runBlocking {
        withStorage(false) { assertExistingFormalCoreReentry() }
    }

    @Test
    fun safExistingFormalCoreReusesItsOperationAndFinishesTags() = runBlocking {
        withStorage(true) { assertExistingFormalCoreReentry() }
    }

    @Test
    fun privateProcessDeathReusesPendingBytesBeforePublication() = runBlocking {
        assertProcessDeathPublication(false)
    }

    @Test
    fun safProcessDeathReusesPendingBytesBeforePublication() = runBlocking {
        assertProcessDeathPublication(true)
    }

    @Test
    fun privateLateUnknownTargetIsPreservedWithoutClaimingIt() = runBlocking {
        withStorage(false) { assertLateUnknownTargetIsPreserved() }
    }

    @Test
    fun safLateUnknownTargetIsPreservedWithoutClaimingIt() = runBlocking {
        withStorage(true) { assertLateUnknownTargetIsPreserved() }
    }

    @Test
    fun privateLateDifferentBytesTargetPreservesBothFiles() = runBlocking {
        withStorage(false) { assertLateUnknownTargetIsPreserved(differentBytes = true) }
    }

    @Test
    fun safLateDifferentBytesTargetPreservesBothFiles() = runBlocking {
        withStorage(true) { assertLateUnknownTargetIsPreserved(differentBytes = true) }
    }

    @Test
    fun privateMissingPendingCannotClaimUnknownFormalTarget() = runBlocking {
        withStorage(false) { assertLateUnknownTargetIsPreserved(pendingMissing = true) }
    }

    @Test
    fun safMissingPendingCannotClaimUnknownFormalTarget() = runBlocking {
        withStorage(true) { assertLateUnknownTargetIsPreserved(pendingMissing = true) }
    }

    @Test
    fun privateInterruptedOwnedPublicationResumesItsTarget() = runBlocking {
        withStorage(false) { assertInterruptedOwnedPublication() }
    }

    @Test
    fun safInterruptedOwnedPublicationResumesItsTarget() = runBlocking {
        withStorage(true) { assertInterruptedOwnedPublication() }
    }

    @Test
    fun privatePublicationReceiptSurvivesPendingCleanupAndMetadataSave() = runBlocking {
        withStorage(false) { assertPublicationReceiptSurvivesCleanup() }
    }

    @Test
    fun safPublicationReceiptSurvivesPendingCleanupAndMetadataSave() = runBlocking {
        withStorage(true) { assertPublicationReceiptSurvivesCleanup() }
    }

    @Test
    fun safStalePendingSizeUsesOnlyTheVerifiedPublicationTarget() = runBlocking {
        withStorage(true) {
            val pending = commit()
            prepareTaggedAudio(pending)
            val promoted = requireNotNull(ManagedDownloadStorage.promoteFinalizedPendingAudio(context, pending)).audio
            assertTrue("TagLib must have changed the old pending size", promoted.sizeBytes > pending.sizeBytes)
            val taggedBytes = read(promoted.reference)
            assertTrue(ManagedDownloadStorage.deletePendingAudioMetadata(context, fileName))
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
            val recovered = requireNotNull(ManagedDownloadStorage.promoteFinalizedPendingAudio(context, pending)).audio
            assertEquals(referenceIdentity(promoted.reference), referenceIdentity(recovered.reference))
            assertEquals(taggedBytes.size.toLong(), recovered.sizeBytes)
            assertArrayEquals(taggedBytes, read(recovered.reference))
            assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        }
    }

    @Test
    fun privateFormalRecoverySealsReceiptBeforeCleanup() = runBlocking {
        withStorage(false) { assertFormalRecoverySealsReceipt() }
    }

    @Test
    fun safFormalRecoverySealsReceiptBeforeCleanup() = runBlocking {
        withStorage(true) { assertFormalRecoverySealsReceipt() }
    }

    @Test
    fun safSealReadbackFailureInvalidatesReplacedMetadataSnapshot() = runBlocking {
        withStorage(true) {
            val pending = commit()
            prepareTaggedAudio(pending)
            val root = ManagedDownloadStorage.resolveRootBlocking(context)
            val promoted = requireNotNull(ManagedDownloadStorage.promotePendingAudio(context, root, pending))
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            val provider = DocumentsContract.buildDocumentUri(
                ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
                ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
            )
            context.contentResolver.call(
                provider, ManagedDownloadMigrationTestDocumentProvider.PUBLICATION_READ_FAULT,
                "$fileName.npmeta.json", null
            )
            assertThrows(Exception::class.java) { ManagedDownloadStorage.sealAudioPublicationReceipt(context, root, promoted) }
            val counters = requireNotNull(context.contentResolver.call(
                provider, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null
            ))
            assertEquals("the failure must follow the actual metadata replacement", 1, counters.getInt("publicationReadFaults"))
            assertNull("a replaced metadata URI must not survive failed readback", ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
            val recovered = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
            assertFalse(recovered.isPendingAudioWrite)
            assertEquals(referenceIdentity(promoted.reference), referenceIdentity(recovered.reference))
            assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        }
    }

    @Test
    fun privateCorruptPendingReceiptCannotBeTreatedAsAbsent() = runBlocking {
        withStorage(false) {
            val pending = commit()
            prepareTaggedAudio(pending)
            val root = ManagedDownloadStorage.resolveRootBlocking(context)
            val promoted = requireNotNull(ManagedDownloadStorage.promotePendingAudio(context, root, pending))
            val receipt = File(pendingMetadataReference(fileName))
            val original = receipt.readBytes()
            receipt.writeText("{\"audioPublicationReceipt\":")
            assertThrows(IOException::class.java) { ManagedDownloadStorage.sealAudioPublicationReceipt(context, root, promoted) }
            assertTrue("failed sealing must retain the known pending receipt", receipt.isFile)
            receipt.writeBytes(original)
            assertFormalRecoveryAfterReceiptReadFault(pending, promoted)
        }
    }

    @Test
    fun safKnownPendingReceiptReadFailureCannotBeTreatedAsAbsent() = runBlocking {
        withStorage(true) {
            val pending = commit()
            prepareTaggedAudio(pending)
            val root = ManagedDownloadStorage.resolveRootBlocking(context)
            val promoted = requireNotNull(ManagedDownloadStorage.promotePendingAudio(context, root, pending))
            val receiptReference = pendingMetadataReference(fileName)
            val original = read(receiptReference)
            val provider = DocumentsContract.buildDocumentUri(
                ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
                ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
            )
            context.contentResolver.call(provider, ManagedDownloadMigrationTestDocumentProvider.PUBLICATION_MISSING_READ, receiptReference, null)
            assertThrows(IOException::class.java) { ManagedDownloadStorage.sealAudioPublicationReceipt(context, root, promoted) }
            val counters = requireNotNull(context.contentResolver.call(provider, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null))
            assertEquals(1, counters.getInt("publicationMissingReads"))
            assertArrayEquals("an unavailable known receipt must not be removed", original, read(receiptReference))
            assertFormalRecoveryAfterReceiptReadFault(pending, promoted)
        }
    }

    @Test
    fun safIncompleteTemporaryDirectoryDiscoveryCannotHideReceipt() = runBlocking {
        withStorage(true) { assertIncompleteReceiptEnumeration(temporaryChildren = false) }
    }

    @Test
    fun safIncompleteTemporaryChildrenCannotHideReceipt() = runBlocking {
        withStorage(true) { assertIncompleteReceiptEnumeration(temporaryChildren = true) }
    }

    @Test
    fun privateMetadataOnlyRetryReusesItsReservedName() = runBlocking {
        withStorage(false) { assertMetadataOnlyReservation(false) }
    }

    @Test
    fun safMetadataOnlyRetryReusesItsReservedName() = runBlocking {
        withStorage(true) { assertMetadataOnlyReservation(false) }
    }

    @Test
    fun privateMetadataOnlyDifferentOwnerKeepsItsReservedName() = runBlocking {
        withStorage(false) { assertMetadataOnlyReservation(true) }
    }

    @Test
    fun safMetadataOnlyDifferentOwnerKeepsItsReservedName() = runBlocking {
        withStorage(true) { assertMetadataOnlyReservation(true) }
    }

    @Test
    fun privateAtomicMoveCannotReplaceExistingTarget() = runBlocking {
        withStorage(false) {
            val pending = File(context.cacheDir, "owned.pending").apply { writeBytes(payload) }
            val original = byteArrayOf(1, 2, 3)
            val target = File(context.cacheDir, "unowned.mp3").apply { writeBytes(original) }
            assertThrows(IOException::class.java) {
                ManagedDownloadStorage.promoteFileTargetWithoutReplacement(pending, target, target.name)
            }
            assertArrayEquals(original, target.readBytes())
            assertArrayEquals(payload, pending.readBytes())
        }
    }

    private suspend fun Fixture.assertMetadataOnlyReservation(differentOwner: Boolean) {
        val receiptOwner = if (differentOwner) UUID.randomUUID().toString() else operationId
        val json = JSONObject().put("operationId", receiptOwner).put("stableKey", song.stableKey())
            .put("audioFileName", fileName).put("artifactState", "COMMITTING").put("downloadFinalized", false)
        assertTrue(ManagedDownloadStorage.writePendingAudioMetadata(context, fileName, json.toString(), receiptOwner))
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        val saved = commit()
        assertEquals(if (differentOwner) "Memories of Kindness (1).mp3" else fileName, saved.logicalName)
        assertArrayEquals(payload, read(saved.reference))
        assertEquals(receiptOwner, readPendingMetadata(fileName).getString("operationId"))
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(1, snapshot.pendingAudioEntries.size)
        assertEquals(operationId, ManagedDownloadStorage.metadataForAudioEntry(snapshot, saved)?.operationId)
    }

    @Test
    fun safBatchCommitKeepsCompleteCacheAndBoundsMetadataReads() = runBlocking {
        withStorage(true) {
            val provider = DocumentsContract.buildDocumentUri(
                ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
                ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
            )
            repeat(32) { index ->
                commit(ownerOperationId = "batch-$index", ownerStableKey = "$index|netease|")
                assertTrue("complete snapshot lost after commit $index", requireNotNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(
                    context, restorePersisted = false
                )).rootEntriesComplete)
            }
            val reads = requireNotNull(context.contentResolver.call(
                provider, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null
            ))
            assertTrue("metadata reads must stay bounded per committed item: $reads", reads.getInt("metadataReads") <= 32 * 8)
            assertTrue("root queries must stay bounded per committed item: $reads", reads.getInt("count") <= 32 * 16)
            assertEquals(32, requireNotNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(
                context, restorePersisted = false
            )).pendingAudioEntries.size)
        }
    }

    private suspend fun assertProcessDeathPublication(saf: Boolean) {
        val phase = InstrumentationRegistry.getArguments().getString("publicationPhase")
        require(phase == null || phase == "seed" || phase == "recover")
        withStorage(saf, phase = phase) {
            val marker = File(context.filesDir, "publication-phase.json")
            if (phase != "recover") {
                val pending = commit()
                marker.writeText(JSONObject().put("pid", Process.myPid())
                    .put("operationId", operationId).put("reference", pending.reference).toString())
            }
            if (phase != "seed") {
                val state = JSONObject(marker.readText())
                if (phase == "recover") assertFalse("recovery must run in another process", state.getInt("pid") == Process.myPid())
                val previousReference = state.getString("reference")
                val recovered = commit(ownerOperationId = state.getString("operationId"))
                assertEquals(referenceIdentity(previousReference), referenceIdentity(recovered.reference))
                assertArrayEquals(payload, read(recovered.reference))
                val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
                assertEquals(listOf(referenceIdentity(previousReference)), snapshot.pendingAudioEntries.map { referenceIdentity(it.reference) })
                assertTaggedPublication(recovered)
                val finalized = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
                assertTrue(finalized.pendingAudioEntries.isEmpty())
                assertEquals(1, finalized.audioEntries.size)
                assertEquals(state.getString("operationId"), ManagedDownloadStorage.metadataForAudioEntry(finalized, finalized.audioEntries.single())?.operationId)
            }
        }
    }

    private suspend fun Fixture.assertInterruptedOwnedPublication() {
        val pending = commit()
        prepareTaggedAudio(pending)
        val taggedBytes = read(pending.reference)
        val partialTarget = createUnownedFile(taggedBytes.copyOf(257))
        ManagedDownloadStorage.recordAudioPublicationTarget(
            context, ManagedDownloadStorage.resolveRootBlocking(context), pending, partialTarget,
            partialTarget.takeIf { it.startsWith("/") }?.let(::publicationFileIdentity)
        )
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        val promoted = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
        assertFalse("an interrupted copy with a matching receipt must resume", promoted.isPendingAudioWrite)
        assertEquals(referenceIdentity(partialTarget), referenceIdentity(promoted.reference))
        assertArrayEquals(taggedBytes, read(promoted.reference))
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertTrue(snapshot.pendingAudioEntries.isEmpty())
        assertEquals(operationId, ManagedDownloadStorage.metadataForAudioEntry(snapshot, promoted)?.operationId)
    }

    private suspend fun Fixture.assertFormalRecoverySealsReceipt() {
        val pending = commit()
        prepareTaggedAudio(pending)
        val root = ManagedDownloadStorage.resolveRootBlocking(context)
        val promoted = requireNotNull(ManagedDownloadStorage.promotePendingAudio(context, root, pending))
        val taggedBytes = read(promoted.reference)
        val recovered = requireNotNull(ManagedDownloadStorage.promoteFinalizedPendingAudio(context, promoted)).audio
        assertTrue(ManagedDownloadStorage.deletePendingAudioMetadata(context, fileName))
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        val metadataEntry = requireNotNull(ManagedDownloadStorage.findMetadataForAudio(context, recovered))
        val metadata = JSONObject(requireNotNull(ManagedDownloadStorage.readText(context, metadataEntry.reference)))
        assertTrue("formal recovery must seal its receipt before cleanup", metadata.has("audioPublicationReceipt"))
        val staleResult = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
        assertEquals(referenceIdentity(recovered.reference), referenceIdentity(staleResult.reference))
        assertArrayEquals(taggedBytes, read(staleResult.reference))
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
    }

    private suspend fun Fixture.assertFormalRecoveryAfterReceiptReadFault(
        pending: ManagedDownloadStorage.StoredEntry,
        promoted: ManagedDownloadStorage.StoredEntry
    ) {
        val recovered = requireNotNull(ManagedDownloadStorage.promoteFinalizedPendingAudio(context, promoted)).audio
        assertTrue(ManagedDownloadStorage.deletePendingAudioMetadata(context, fileName))
        val staleResult = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
        assertEquals(referenceIdentity(recovered.reference), referenceIdentity(staleResult.reference))
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
    }

    private suspend fun Fixture.assertIncompleteReceiptEnumeration(temporaryChildren: Boolean) {
        val pending = commit()
        prepareTaggedAudio(pending)
        val root = ManagedDownloadStorage.resolveRootBlocking(context)
        val promoted = requireNotNull(ManagedDownloadStorage.promotePendingAudio(context, root, pending))
        val receiptReference = pendingMetadataReference(fileName)
        val original = read(receiptReference)
        val provider = DocumentsContract.buildDocumentUri(
            ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
            ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
        )
        val parent = if (temporaryChildren) {
            val tree = DocumentsContract.buildTreeDocumentUri(ManagedDownloadMigrationTestDocumentProvider.AUTHORITY, ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
            requireNotNull(requireNotNull(DocumentFile.fromTreeUri(context, tree)).findFile(".tmp")).uri
        } else provider
        ManagedDownloadStorage.treeChildRegistry.clear()
        context.contentResolver.call(provider, ManagedDownloadMigrationTestDocumentProvider.PUBLICATION_CHILDREN_FAULT, parent.toString(), null)
        val failure = runCatching { ManagedDownloadStorage.sealAudioPublicationReceipt(context, root, promoted) }.exceptionOrNull()
        val counters = requireNotNull(context.contentResolver.call(provider, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null))
        assertEquals("the requested directory query must be incomplete exactly once", 1, counters.getInt("publicationChildrenFaults"))
        assertTrue("incomplete enumeration cannot authorize receipt cleanup", failure is IOException)
        assertArrayEquals(original, read(receiptReference))
        assertFormalRecoveryAfterReceiptReadFault(pending, promoted)
    }

    private suspend fun Fixture.assertPublicationReceiptSurvivesCleanup() {
        val pending = commit()
        prepareTaggedAudio(pending)
        val promoted = requireNotNull(ManagedDownloadStorage.promoteFinalizedPendingAudio(context, pending)).audio
        val taggedBytes = read(promoted.reference)
        assertTrue(ManagedDownloadStorage.deletePendingAudioMetadata(context, fileName))
        val metadataEntry = requireNotNull(ManagedDownloadStorage.findMetadataForAudio(context, promoted))
        val metadata = JSONObject(requireNotNull(ManagedDownloadStorage.readText(context, metadataEntry.reference)))
        assertTrue("publication receipt must be sealed before temporary metadata is removed", metadata.has("audioPublicationReceipt"))
        metadata.remove("audioPublicationReceipt")
        assertTrue(ManagedDownloadStorage.saveMetadata(context, promoted, metadata.toString()))
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        val recovered = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
        assertFalse(recovered.isPendingAudioWrite)
        assertEquals(referenceIdentity(promoted.reference), referenceIdentity(recovered.reference))
        assertArrayEquals(taggedBytes, read(recovered.reference))
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        metadata.put("operationId", "different-owner").put("stableKey", "5678|netease|")
        assertTrue(ManagedDownloadStorage.saveMetadata(context, promoted, metadata.toString()))
        val changedEntry = requireNotNull(ManagedDownloadStorage.findMetadataForAudio(context, promoted))
        val changedMetadata = JSONObject(requireNotNull(ManagedDownloadStorage.readText(context, changedEntry.reference)))
        assertFalse("publication ownership must not follow metadata for another operation", changedMetadata.has("audioPublicationReceipt"))
    }

    private suspend fun Fixture.assertLateUnknownTargetIsPreserved(differentBytes: Boolean = false, pendingMissing: Boolean = false) {
        val pending = commit()
        prepareTaggedAudio(pending)
        val taggedBytes = read(pending.reference)
        val unknownBytes = taggedBytes.copyOf().also { if (differentBytes) it[it.lastIndex] = (it.last() + 1).toByte() }
        val unknownReference = createUnownedFile(unknownBytes)
        if (pendingMissing) {
            if (pending.reference.startsWith("/")) assertTrue(File(pending.reference).delete())
            else assertEquals(1, context.contentResolver.delete(Uri.parse(pending.reference), null, null))
        }
        val result = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
        assertTrue("equal bytes cannot identify an externally created target as this operation", result.isPendingAudioWrite)
        assertEquals(referenceIdentity(pending.reference), referenceIdentity(result.reference))
        if (!pendingMissing) assertArrayEquals(taggedBytes, read(pending.reference))
        assertArrayEquals(unknownBytes, read(unknownReference))
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(if (pendingMissing) 0 else 1, snapshot.pendingAudioEntries.size)
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
    }

    private suspend fun Fixture.assertExistingFormalCoreReentry() {
        val pending = commit()
        val formal = requireNotNull(ManagedDownloadStorage.promoteCoreCommittedPendingAudio(
            context, pending, promotePendingMetadata = true
        ))
        assertFalse(formal.isPendingAudioWrite)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        val reused = commit()
        assertEquals(referenceIdentity(formal.reference), referenceIdentity(reused.reference))
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        assertArrayEquals(payload, read(formal.reference))
        assertTaggedPublication(reused)
    }

    private suspend fun Fixture.assertCoreRemainsPending() {
        val pending = commit()
        val result = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)

        assertTrue("core-only audio must retain its pending name", result.isPendingAudioWrite)
        assertEquals(pending.reference, result.reference)
        assertArrayEquals(payload, read(result.reference))
        assertFalse("an untagged core must not appear under its final audio name", finalNames().contains(fileName))
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val metadata = requireNotNull(ManagedDownloadStorage.metadataForAudioEntry(snapshot, pending))
        assertEquals(operationId, metadata.operationId)
        assertEquals(song.stableKey(), metadata.stableKey)
        assertFalse(metadata.downloadFinalized == true)
    }

    private suspend fun Fixture.assertCommitReentryReusesPending(coldCache: Boolean = false) {
        val first = commit()
        val initialSnapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(
            "the commit boundary must persist its own pending identity before writing audio",
            operationId,
            initialSnapshot.pendingMetadataByAudioName[fileName]?.operationId
        )
        if (coldCache) {
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
        }
        val second = commit()

        assertEquals("retry must reuse the existing durable operation receipt", referenceIdentity(first.reference), referenceIdentity(second.reference))
        assertEquals(fileName, second.logicalName)
        assertArrayEquals(payload, read(first.reference))
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(listOf(referenceIdentity(first.reference)), snapshot.pendingAudioEntries.map { referenceIdentity(it.reference) })
        assertTrue(finalNames().none { it.endsWith(".mp3") })
    }

    private suspend fun Fixture.assertDifferentOwnerIsPreserved() {
        val first = commit()
        val otherOperation = UUID.randomUUID().toString()
        val second = commit(ownerOperationId = otherOperation, ownerStableKey = "5678|bilibili|")

        assertFalse(first.reference == second.reference)
        assertArrayEquals(payload, read(first.reference))
        assertArrayEquals(payload, read(second.reference))
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val firstMetadata = requireNotNull(ManagedDownloadStorage.metadataForAudioEntry(snapshot, first))
        val secondMetadata = requireNotNull(ManagedDownloadStorage.metadataForAudioEntry(snapshot, second))
        assertEquals(operationId, firstMetadata.operationId)
        assertEquals(song.stableKey(), firstMetadata.stableKey)
        assertEquals(otherOperation, secondMetadata.operationId)
        assertEquals("5678|bilibili|", secondMetadata.stableKey)
    }

    private suspend fun Fixture.assertUnownedFileIsPreserved() {
        val original = byteArrayOf(7, 8, 9, 10)
        val reference = createUnownedFile(original)
        val saved = commit()

        assertArrayEquals(original, read(reference))
        assertArrayEquals(payload, read(saved.reference))
        assertTrue(saved.isPendingAudioWrite)
        assertEquals("Memories of Kindness (1).mp3", saved.logicalName)
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
            context, forceRefresh = true, includeMetadataLessAudioForLegacyUpgrade = true
        )
        val unowned = snapshot.audioEntries.single {
            referenceIdentity(it.reference) == referenceIdentity(reference)
        }
        assertNull("collision metadata must not claim the unowned original", ManagedDownloadStorage.metadataForAudioEntry(snapshot, unowned))
    }

    private suspend fun Fixture.assertDifferentBytesArePreserved() {
        val first = commit()
        val differentBytes = payload.copyOf().also { it[it.lastIndex] = 5 }
        assertThrows(IOException::class.java) {
            runBlocking { commit(differentBytes) }
        }

        assertArrayEquals(payload, read(first.reference))
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(listOf(referenceIdentity(first.reference)), snapshot.pendingAudioEntries.map { referenceIdentity(it.reference) })
    }

    private suspend fun Fixture.assertTaggedPublication(existing: ManagedDownloadStorage.StoredEntry? = null) {
        val pending = existing ?: commit()
        prepareTaggedAudio(pending)
        val promoted = requireNotNull(ManagedDownloadStorage.promoteFinalizedPendingAudio(context, pending)).audio

        assertFalse(promoted.isPendingAudioWrite)
        val staleReferenceResult = DownloadCorePublicationCoordinator().promoteBeforePublication(context, song, pending)
        assertEquals(referenceIdentity(promoted.reference), referenceIdentity(staleReferenceResult.reference))
        assertEquals(listOf(fileName), finalNames().filter { it.endsWith(".mp3") })
        val descriptor = if (promoted.reference.startsWith("/")) {
            ParcelFileDescriptor.open(File(promoted.reference), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(promoted.reference), "r"))
        }
        descriptor.use {
            val tags = requireNotNull(TagLib.getMetadata(it.dup().detachFd(), false)).propertyMap
            assertEquals(song.name, tags["TITLE"]?.single())
            assertEquals(song.artist, tags["ARTIST"]?.single())
            assertEquals(song.stableKey(), tags["NERI_STABLE_KEY"]?.single())
        }
    }

    private suspend fun Fixture.prepareTaggedAudio(pending: ManagedDownloadStorage.StoredEntry) {
        assertEquals(
            DownloadedAudioTagWriteOutcome.SUCCESS,
            DownloadedAudioTagWriter.write(context, pending, song, null, standardizedLyricEmbeddingEnabled = true)
        )
        if (pending.isPendingAudioWrite) assertTrue(finalNames().none { it.endsWith(".mp3") })
        val metadata = JSONObject()
            .put("stableKey", song.stableKey())
            .put("songId", song.id)
            .put("name", song.name)
            .put("artist", song.artist)
            .put("audioFileName", fileName)
            .put("operationId", operationId)
            .put("downloadFinalized", true)
            .put("metadataEmbeddingState", "EMBEDDED_VERIFIED")
            .put("artifactState", "COMPLETE")
        assertTrue(ManagedDownloadStorage.saveMetadata(context, pending, metadata.toString()))
    }

    private fun referenceIdentity(reference: String): String {
        if (reference.startsWith("/")) return File(reference).canonicalPath
        val uri = Uri.parse(reference)
        return "${uri.authority}|${DocumentsContract.getDocumentId(uri)}"
    }

    private suspend fun withStorage(saf: Boolean, phase: String? = null, block: suspend Fixture.() -> Unit) =
        GlobalDownloadManager.startupRecoveryMutex.withLock {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val previousDirectory = ManagedDownloadStorage.configuredDirectoryUri()
        val sessionName = if (phase == null) UUID.randomUUID().toString() else "phase-v1-${if (saf) "saf" else "private"}"
        val directory = File(base.cacheDir, "core-publication-$sessionName")
        if (phase == "recover") {
            check(File(directory, "files/publication-phase.json").isFile) { "publication seed is missing" }
        } else {
            check(!directory.exists()) { "unfinished publication fixture exists; recover it before reseeding" }
            check(directory.mkdirs())
        }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = File(directory, type ?: "external").apply { mkdirs() }
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        val providerUri = DocumentsContract.buildDocumentUri(
            ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
            ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
            ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
        )
        if (saf && phase != "recover") base.contentResolver.call(providerUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
        ManagedDownloadStorage.primeSettings(if (saf) treeUri.toString() else null, null)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        try {
            val owner = if (phase == "recover") {
                JSONObject(File(context.filesDir, "publication-phase.json").readText()).getString("operationId")
            } else UUID.randomUUID().toString()
            Fixture(context, if (saf) treeUri else null, owner).block()
        } finally {
            ManagedDownloadStorage.primeSettings(previousDirectory, null)
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
            if (phase != "seed") {
                if (saf) base.contentResolver.call(providerUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                directory.deleteRecursively()
            }
        }
    }

    private class Fixture(val context: Context, private val treeUri: Uri?, val operationId: String) {
        val song = SongItem(
            id = 1234L, name = "Memories of Kindness", artist = "鹿乃", album = "Memories of Kindness",
            albumId = 0L, durationMs = 1300L, coverUrl = null, sourceStableKey = "1234|netease|"
        )
        val fileName = "Memories of Kindness.mp3"
        val payload = ByteArray(417 * 50).apply {
            repeat(50) { frame ->
                byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64).copyInto(this, frame * 417)
            }
        }

        suspend fun commit(
            bytes: ByteArray = payload,
            ownerOperationId: String = operationId,
            ownerStableKey: String = song.stableKey()
        ): ManagedDownloadStorage.StoredEntry {
            val working = File.createTempFile("core-", ".mp3", context.cacheDir).apply { writeBytes(bytes) }
            val pendingMetadata = JSONObject()
                .put("stableKey", ownerStableKey)
                .put("songId", song.id)
                .put("name", song.name)
                .put("artist", song.artist)
                .put("album", song.album)
                .put("audioFileName", fileName)
                .put("operationId", ownerOperationId)
                .put("downloadFinalized", false)
                .put("artifactState", "COMMITTING")
                .toString()
            return try {
                ManagedDownloadStorage.saveAudioFromTemp(
                    context = context, tempFile = working, fileName = fileName, mimeType = "audio/mpeg",
                    expectedSizeBytes = bytes.size.toLong(), transferSizeVerified = true,
                    pendingMetadataJson = pendingMetadata,
                    seedMetadataJson = JSONObject(pendingMetadata).put("artifactState", "CORE_COMMITTED").toString()
                )
            } finally {
                working.delete()
            }
        }

        fun read(reference: String): ByteArray = if (reference.startsWith("/")) {
            File(reference).readBytes()
        } else {
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(reference))).use { it.readBytes() }
        }

        fun readPendingMetadata(audioName: String): JSONObject {
            return JSONObject(read(pendingMetadataReference(audioName)).toString(Charsets.UTF_8))
        }

        fun pendingMetadataReference(audioName: String): String {
            val name = "$audioName.npmeta.pending.json"
            return treeUri?.let { uri ->
                val root = requireNotNull(DocumentFile.fromTreeUri(context, uri))
                requireNotNull(requireNotNull(root.findFile(".tmp")).findFile(name)).uri.toString()
            } ?: File(privateRoot(), ".tmp/$name").absolutePath
        }

        fun finalNames(): List<String> = treeUri?.let { uri ->
            requireNotNull(DocumentFile.fromTreeUri(context, uri)).listFiles().mapNotNull { it.name }
        } ?: privateRoot().listFiles().orEmpty().map { it.name }

        fun createUnownedFile(bytes: ByteArray): String = treeUri?.let { uri ->
            val root = requireNotNull(DocumentFile.fromTreeUri(context, uri))
            val file = requireNotNull(root.createFile("application/octet-stream", fileName))
            requireNotNull(context.contentResolver.openOutputStream(file.uri)).use { it.write(bytes) }
            file.uri.toString()
        } ?: File(privateRoot().apply { mkdirs() }, fileName).apply { writeBytes(bytes) }.absolutePath

        private fun privateRoot() = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), ROOT_DIR_NAME)
    }
}
