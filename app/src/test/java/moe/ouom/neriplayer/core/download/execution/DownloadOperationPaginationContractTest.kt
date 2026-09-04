package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertEquals
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
        assertTrue(progressReader.contains("queueOrder = header.queueOrder"))
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
            "suspend fun findByStatesInLibraryAfterOperationId("
        )
        val queryIndex = source.lastIndexOf("@Query(", functionIndex)
        val keysetBody = source.substring(queryIndex, functionIndex)

        assertTrue(keysetBody.contains("ORDER BY operation_id ASC"))
        assertFalse(keysetBody.contains("updated_at_ms"))
    }

    @Test
    fun `pump paging uses the full queue ordering and advances past malformed rows`() {
        val daoSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadOperationDao.kt"
        )
        val functionIndex = daoSource.indexOf(
            "suspend fun findSchedulableForPumpAfterCursorHeaders"
        )
        val queryIndex = daoSource.lastIndexOf("@Query(", functionIndex)
        val query = daoSource.substring(queryIndex, functionIndex)

        assertTrue(query.contains("queue_order > :afterQueueOrder"))
        assertTrue(query.contains("updated_at_ms > :afterUpdatedAtMs"))
        assertTrue(query.contains("operation_id > :afterOperationId"))
        assertTrue(query.contains("ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC"))
        assertFalse(query.contains("OFFSET"))

        val roomStoreSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadExecutionRoomStore.kt"
        )
        val pumpReader = roomStoreSource.substringAfter(
            "suspend fun listSchedulableForPumpPage("
        ).substringBefore("suspend fun listByStates(")
        val transactionIndex = pumpReader.indexOf("database.withTransaction {")
        val headerQueryIndex = pumpReader.indexOf(
            "findSchedulableForPumpAfterCursorHeaders("
        )
        val payloadReadIndex = pumpReader.indexOf("readRequestFromHeader(dao, header)")
        val transactionalRead = pumpReader.substringAfter(
            "val (headers, decodedRequests) = database.withTransaction {"
        ).substringBefore("\n        }")
        val nextCursorIndex = pumpReader.indexOf("val nextCursor = headers.lastOrNull()")
        val requestMappingIndex = pumpReader.indexOf("val requests = decodedRequests.mapNotNull")

        assertTrue(transactionIndex >= 0)
        assertTrue(headerQueryIndex > transactionIndex)
        assertTrue(payloadReadIndex > headerQueryIndex)
        assertTrue(
            transactionalRead.contains("findSchedulableForPumpAfterCursorHeaders(")
        )
        assertTrue(transactionalRead.contains("readRequestFromHeader(dao, header)"))
        assertTrue(nextCursorIndex >= 0)
        assertTrue(requestMappingIndex > nextCursorIndex)
        assertTrue(pumpReader.contains("headers.size == boundedLimit"))
        assertTrue(pumpReader.contains("malformedHeaders.forEach"))
    }

    @Test
    fun `progress checkpoint SQL keeps byte and known total watermarks`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadOperationDao.kt"
        )
        val checkpointQueries = source.substringAfter(
            "suspend fun replaceMalformedReusablePayload"
        ).substringBefore("suspend fun requestUserStop")

        assertEquals(
            2,
            Regex("MAX\\(bytes_written, :bytesWritten\\)")
                .findAll(checkpointQueries)
                .count()
        )
        assertEquals(
            2,
            Regex("WHEN :totalBytes IS NULL OR :totalBytes <= 0 THEN total_bytes")
                .findAll(checkpointQueries)
                .count()
        )
        assertEquals(
            2,
            Regex("WHEN total_bytes IS NULL OR total_bytes <= 0 THEN :totalBytes")
                .findAll(checkpointQueries)
                .count()
        )
        assertEquals(
            2,
            Regex("WHEN :totalBytes > total_bytes THEN :totalBytes ELSE total_bytes END")
                .findAll(checkpointQueries)
                .count()
        )
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
