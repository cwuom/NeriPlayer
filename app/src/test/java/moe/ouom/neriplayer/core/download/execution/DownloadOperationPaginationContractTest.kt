package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOperationPaginationContractTest {
    @Test
    fun `room paging uses operation id keyset instead of mutable offset`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadOperationDao.kt"
        )

        assertTrue(source.contains("operation_id > :afterOperationId"))
        assertTrue(source.contains("findByStatesInLibraryAfterOperationId"))
        assertTrue(source.contains("findByStatesAfterOperationId"))
        assertTrue(source.contains("findAllOperationIdentitiesAfterOperationId"))
    }

    @Test
    fun `recovery readers advance a monotonic cursor and restore queue ordering`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadExecutionRoomStore.kt"
        )
        val stateReader = source.substringAfter("suspend fun listByStates(")
            .substringBefore("/**\n     * 目录切换")
        val progressReader = source.substringAfter("suspend fun listProgressEntries(")
            .substringBefore("/** 跨存储根读取进度检查点")

        assertTrue(stateReader.contains("afterOperationId"))
        assertTrue(stateReader.contains("nextOperationId <= afterOperationId"))
        assertTrue(stateReader.contains("entries.sortWith"))
        assertTrue(progressReader.contains("afterOperationId"))
        assertTrue(progressReader.contains("queueOrder = entity.queueOrder"))
        assertTrue(progressReader.contains("entries.sortWith"))
        assertFalse(stateReader.contains("OFFSET"))
        assertFalse(progressReader.contains("OFFSET"))
    }

    @Test
    fun `keyset contract explains why mutable timestamps are not cursors`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadOperationDao.kt"
        )
        val functionIndex = source.indexOf(
            "suspend fun findByStatesInLibraryAfterOperationId"
        )
        val queryIndex = source.lastIndexOf("@Query(", functionIndex)
        val keysetBody = source.substring(queryIndex, functionIndex)

        assertTrue(keysetBody.contains("ORDER BY operation_id ASC"))
        assertFalse(keysetBody.contains("updated_at_ms"))
    }

    private fun readSource(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $relativePath")
    }
}
