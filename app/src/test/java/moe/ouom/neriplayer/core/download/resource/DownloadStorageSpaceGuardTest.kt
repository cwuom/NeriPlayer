package moe.ouom.neriplayer.core.download.resource

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageSpaceGuardTest {

    @Test
    fun `reservations account for concurrent owners`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 100L }
            )
            val first = guard.reserve(root, "first", expectedAdditionalBytes = 60L)
            assertThrows(DownloadStorageSpaceException::class.java) {
                guard.reserve(root, "second", expectedAdditionalBytes = 40L)
            }
            first.close()
            val second = guard.reserve(root, "second", expectedAdditionalBytes = 40L)
            second.close()
            assertEquals(0, guard.snapshot(root).ownerCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unknown output extends reservation before writing`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 100L }
            )
            val output = ByteArrayOutputStream()
            val guarded = guard.guardOutput(
                output = output,
                root = root,
                ownerKey = "stream"
            )
            guarded.write(ByteArray(30))
            assertTrue(guard.snapshot(root).reservedBytes >= 30L)
            guarded.close()
            assertEquals(0, guard.snapshot(root).reservedBytes)
            assertEquals(30, output.size())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `space failure happens before bytes are written`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 20L }
            )
            val output = ByteArrayOutputStream()
            val guarded = guard.guardOutput(
                output = output,
                root = root,
                ownerKey = "stream"
            )
            assertThrows(DownloadStorageSpaceException::class.java) {
                guarded.write(ByteArray(20))
            }
            guarded.close()
            assertEquals(0, output.size())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reservation failure closes the already opened output`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 0L }
            )
            val output = TrackingOutputStream()

            assertThrows(DownloadStorageSpaceException::class.java) {
                guard.guardOutput(output, root, "full")
            }
            assertTrue(output.closed)
            assertEquals(0, guard.snapshot(root).ownerCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid byte ranges are rejected before reserving space`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 1L,
                unknownReservationBytes = 1L,
                usableSpaceOf = { 100L }
            )
            val guarded = guard.guardOutput(ByteArrayOutputStream(), root, "range")
            assertThrows(IllegalArgumentException::class.java) {
                guarded.write(ByteArray(4), 3, 2)
            }
            assertEquals(1, guard.snapshot(root).ownerCount)
            guarded.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `lease close is idempotent`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 1L,
                unknownReservationBytes = 1L,
                usableSpaceOf = { 100L }
            )
            val lease = guard.reserve(root, "owner", expectedAdditionalBytes = 10L)
            lease.close()
            lease.close()
            assertEquals(0, guard.snapshot(root).reservedBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `provider no space errors enter the storage recovery classification`() {
        val error = IOException("write failed: ENOSPC")

        assertTrue(containsDownloadStorageSpaceFailure(error))
        assertTrue(
            containsDownloadStorageSpaceFailure(
                IllegalStateException("storage full", error)
            )
        )
    }

    private fun temporaryRoot(): File {
        return Files.createTempDirectory("download-space-guard").toFile()
    }

    private class TrackingOutputStream : OutputStream() {
        var closed = false

        override fun write(b: Int) = Unit

        override fun close() {
            closed = true
        }
    }
}
