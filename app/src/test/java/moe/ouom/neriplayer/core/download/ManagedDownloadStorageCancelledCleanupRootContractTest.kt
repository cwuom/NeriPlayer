package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageCancelledCleanupRootContractTest {

    @Test
    fun `cancelled cleanup accepts an explicit source root and keeps the default root`() {
        val source = readSource()
        val cleanup = methodBody(source, "cleanupCancelledPendingDownloadArtifacts")

        assertTrue(source.contains("stableKey: String,"))
        assertTrue(source.contains("operations: Collection<CancelledPendingDownloadOperation>,"))
        assertTrue(source.contains("directoryUri: String? = null"))
        assertTrue(source.contains("directoryUri = directoryUri"))
        assertTrue(cleanup.contains("normalizedDirectoryUri"))
        assertTrue(cleanup.contains("resolveRootForOperation("))
        assertTrue(cleanup.contains("useDefaultRootWhenDirectoryUriMissing"))
    }

    @Test
    fun `source root failures preserve pending evidence`() {
        val source = readSource()
        val cleanup = methodBody(source, "cleanupCancelledPendingDownloadArtifacts")

        assertTrue(cleanup.contains("catch (error: kotlinx.coroutines.CancellationException)"))
        assertTrue(cleanup.contains("throw error"))
        assertTrue(cleanup.contains("catch (error: SecurityException)"))
        assertTrue(cleanup.contains("failedCount = normalizedOperations.size"))
        assertTrue(cleanup.contains("保留等待恢复"))
    }

    @Test
    fun `pending audio deletion gates pending metadata deletion`() {
        val source = readSource()
        val cleanup = methodBody(source, "cleanupCancelledPendingDownloadArtifacts")
        val audioDelete = cleanup.indexOf(
            "val deletedAudioReferences = deleteReferencesInternalConcurrently"
        )
        val metadataGate = cleanup.indexOf(
            "val metadataEntriesReadyForDeletion ="
        )
        val metadataDelete = cleanup.indexOf(
            "val deletedMetadataReferences = deleteReferencesInternalConcurrently"
        )
        assertTrue(audioDelete >= 0)
        assertTrue(metadataGate > audioDelete)
        assertTrue(metadataDelete > metadataGate)
        assertTrue(cleanup.contains("audio.reference in deletedAudioReferences"))
        assertTrue(cleanup.contains("deferredMetadataReferences"))
        assertTrue(cleanup.contains("lastIndexOf(PENDING_AUDIO_WRITE_MARKER)"))
        assertTrue(cleanup.contains("recordTerminalCleanupFor(pendingAudioEntries)"))
        assertTrue(
            cleanup.indexOf("val metadataTargetsRecorded = recordTerminalCleanupFor") >
                audioDelete
        )
    }

    @Test
    fun `clear cleanup parses only metadata paired with pending artifacts`() {
        val source = readSource()
        val cancelledCleanup = methodBody(source, "cleanupCancelledPendingDownloadArtifacts")
        val orphanCleanup = methodBody(source, "cleanupUnownedPendingDownloadArtifactsForClear")

        assertTrue(cancelledCleanup.contains("metadataEntriesForPendingArtifacts(rootEntries)"))
        assertTrue(
            cancelledCleanup.contains("parseDownloadedAudioMetadataEntriesBatch(")
        )
        assertTrue(orphanCleanup.contains("metadataEntriesForPendingArtifacts(allEntries)"))
        assertTrue(
            source.contains("internal fun ManagedDownloadStorage.metadataEntriesForPendingArtifacts(")
        )
        assertTrue(
            source.contains(
                "internal suspend fun ManagedDownloadStorage.parseDownloadedAudioMetadataEntriesBatch("
            )
        )
        assertTrue(source.contains("METADATA_SCAN_PARALLELISM"))
    }

    @Test
    fun `metadata selection keeps formal metadata for a pending audio name`() {
        val source = readSource()
        val helper = methodBody(source, "metadataEntriesForPendingArtifacts")

        assertTrue(helper.contains("entry.isPendingAudioWrite"))
        assertTrue(helper.contains("PENDING_AUDIO_WRITE_MARKER"))
        assertTrue(helper.contains("isPendingMetadataName"))
        assertTrue(helper.contains("audioName in pendingAudioNames"))
    }

    private fun readSource(): String {
        val relativePath =
            "src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        val candidate = sequenceOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath")
        ).firstOrNull(File::isFile)
            ?: error("project source file not found: $relativePath")
        return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver
            .resolve(candidate)
            .readText()
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )
}
