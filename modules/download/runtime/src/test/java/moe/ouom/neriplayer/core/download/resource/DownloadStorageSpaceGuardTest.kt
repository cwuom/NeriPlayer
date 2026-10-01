package moe.ouom.neriplayer.core.download.resource

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `different operation directories share reservations on the same volume`() {
        val firstRoot = temporaryRoot()
        val secondRoot = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 100L },
                storageVolumeKeyOf = { "shared-volume" }
            )
            val first = guard.reserve(
                root = firstRoot,
                ownerKey = "first",
                expectedAdditionalBytes = 60L
            )

            assertThrows(DownloadStorageSpaceException::class.java) {
                guard.reserve(
                    root = secondRoot,
                    ownerKey = "second",
                    expectedAdditionalBytes = 40L
                )
            }
            assertEquals(60L, guard.snapshot(secondRoot).reservedBytes)

            first.close()
            val second = guard.reserve(
                root = secondRoot,
                ownerKey = "second",
                expectedAdditionalBytes = 40L
            )
            second.close()
        } finally {
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
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
            assertEquals(0L, guard.snapshot(root).reservedBytes)
            guarded.close()
            assertEquals(0, guard.snapshot(root).reservedBytes)
            assertEquals(30, output.size())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `written bytes are not counted again as reserved capacity`() {
        val root = temporaryRoot()
        try {
            var usableBytes = 100L
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    usableBytes -= length
                }
            }
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { usableBytes }
            )
            val guarded = guard.guardOutput(output, root, "stream")

            guarded.write(ByteArray(40))
            guarded.write(ByteArray(40))

            assertEquals(80, output.size())
            assertEquals(20L, usableBytes)
            assertEquals(0L, guard.snapshot(root).reservedBytes)
            guarded.close()
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
        assertEquals(
            DownloadStorageSpaceFailureKind.PROVIDER_FAILURE,
            classifyDownloadStorageSpaceFailure(error)
        )
        assertTrue(
            containsDownloadStorageSpaceFailure(
                IllegalStateException("storage full", error)
            )
        )
    }

    @Test
    fun `reservation contention is not treated as physical exhaustion`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 100L }
            )
            val first = guard.reserve(root, "first", expectedAdditionalBytes = 60L)
            val error = assertThrows(DownloadStorageSpaceException::class.java) {
                guard.reserve(root, "second", expectedAdditionalBytes = 40L)
            }
            assertEquals(
                DownloadStorageSpaceFailureKind.RESERVATION_CONTENTION,
                error.failureKind
            )
            assertFalse(error.failureKind.isDefinitive)
            first.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `lease growth accounts for its own reservation when classifying exhaustion`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { 100L }
            )
            val lease = guard.reserve(root, "growing", expectedAdditionalBytes = 60L)
            val error = assertThrows(DownloadStorageSpaceException::class.java) {
                lease.ensureAdditionalBytes(91L)
            }
            assertEquals(60L, error.ownerReservedBytes)
            assertEquals(31L, error.requestedBytes)
            assertEquals(
                DownloadStorageSpaceFailureKind.CAPACITY_EXHAUSTED,
                error.failureKind
            )
            assertTrue(error.failureKind.isDefinitive)
            lease.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unavailable space probe does not cancel downloads`() {
        val root = temporaryRoot()
        try {
            val guard = DownloadStorageSpaceGuard(
                minimumFreeBytes = 10L,
                unknownReservationBytes = 5L,
                usableSpaceOf = { throw IOException("probe unavailable") }
            )
            val error = assertThrows(DownloadStorageSpaceException::class.java) {
                guard.reserve(root, "probe", expectedAdditionalBytes = 1L)
            }
            assertEquals(
                DownloadStorageSpaceFailureKind.PROBE_UNAVAILABLE,
                error.failureKind
            )
            assertFalse(error.failureKind.isDefinitive)
            assertTrue(containsDownloadStorageSpaceFailure(error))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `capacity exhaustion is definitive`() {
        val error = DownloadStorageSpaceException(
            rootPath = "/tmp",
            usableBytes = 4L,
            reservedBytes = 0L,
            requestedBytes = 8L,
            minimumFreeBytes = 2L
        )

        assertEquals(
            DownloadStorageSpaceFailureKind.CAPACITY_EXHAUSTED,
            error.failureKind
        )
        assertTrue(error.failureKind.isDefinitive)
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
