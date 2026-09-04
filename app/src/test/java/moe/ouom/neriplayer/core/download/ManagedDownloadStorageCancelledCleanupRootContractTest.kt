package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageCancelledCleanupRootContractTest {

    @Test
    fun `cancelled cleanup accepts an explicit source root and keeps the default root`() {
        val source = readSource()
        val single = source.substringAfter(
            "internal suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        stableKey: String,"
        ).substringBefore(
            "/** 清空全部任务时基于一次完整根目录快照解析所有 operation 的待处理文件对"
        )
        val batch = source.substringAfter(
            "internal suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        operations: Collection<CancelledPendingDownloadOperation>,"
        ).substringBefore(
            "/**\n     * 清空任务时收敛没有 operation 凭据的临时文件"
        )

        assertTrue(single.contains("directoryUri: String? = null"))
        assertTrue(single.contains("directoryUri = directoryUri"))
        assertTrue(batch.contains("directoryUri: String? = null"))
        assertTrue(batch.contains("normalizedDirectoryUri"))
        assertTrue(batch.contains("resolveRootForOperation("))
        assertTrue(batch.contains("useDefaultRootWhenDirectoryUriMissing"))
    }

    @Test
    fun `source root failures preserve pending evidence`() {
        val source = readSource()
        val cleanup = source.substringAfter(
            "internal suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        operations: Collection<CancelledPendingDownloadOperation>,"
        ).substringBefore(
            "/**\n     * 清空任务时收敛没有 operation 凭据的临时文件"
        )

        assertTrue(cleanup.contains("catch (error: kotlinx.coroutines.CancellationException)"))
        assertTrue(cleanup.contains("throw error"))
        assertTrue(cleanup.contains("catch (error: SecurityException)"))
        assertTrue(cleanup.contains("failedCount = normalizedOperations.size"))
        assertTrue(cleanup.contains("保留等待恢复"))
    }

    @Test
    fun `pending audio deletion gates pending metadata deletion`() {
        val source = readSource()
        val cleanup = source.substringAfter(
            "internal suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        operations: Collection<CancelledPendingDownloadOperation>,"
        ).substringBefore(
            "/**\n     * 清空任务时收敛没有 operation 凭据的临时文件"
        )
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
        val cancelledCleanup = source.substringAfter(
            "internal suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        operations: Collection<CancelledPendingDownloadOperation>,"
        ).substringBefore(
            "/**\n     * 清空任务时收敛没有 operation 凭据的临时文件"
        )
        val orphanCleanup = source.substringAfter(
            "internal suspend fun cleanupUnownedPendingDownloadArtifactsForClear("
        ).substringBefore(
            "/** 按入队时记录的根目录回放所有持久终态清理"
        )

        assertTrue(cancelledCleanup.contains("metadataEntriesForPendingArtifacts(rootEntries)"))
        assertTrue(
            cancelledCleanup.contains("parseDownloadedAudioMetadataEntriesBatch(")
        )
        assertTrue(orphanCleanup.contains("metadataEntriesForPendingArtifacts(allEntries)"))
        assertTrue(
            source.contains("private fun metadataEntriesForPendingArtifacts(")
        )
        assertTrue(
            source.contains("private suspend fun parseDownloadedAudioMetadataEntriesBatch(")
        )
        assertTrue(source.contains("METADATA_SCAN_PARALLELISM"))
    }

    @Test
    fun `metadata selection keeps formal metadata for a pending audio name`() {
        val source = readSource()
        val helper = source.substringAfter(
            "private fun metadataEntriesForPendingArtifacts("
        ).substringBefore(
            "/** 并行读取清理所需的 metadata"
        )

        assertTrue(helper.contains("entry.isPendingAudioWrite"))
        assertTrue(helper.contains("PENDING_AUDIO_WRITE_MARKER"))
        assertTrue(helper.contains("isPendingMetadataName"))
        assertTrue(helper.contains("audioName in pendingAudioNames"))
    }

    private fun readSource(): String {
        val relativePath =
            "src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        return sequenceOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("project source file not found: $relativePath")
    }
}
