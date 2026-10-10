package moe.ouom.neriplayer.core.download.resource

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadStorageSpaceGuardBoundaryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `guard configuration rejects negative free space and empty unknown reservations`() {
        val negativeFree = assertThrows(IllegalArgumentException::class.java) {
            DownloadStorageSpaceGuard(minimumFreeBytes = -1L, usableSpaceOf = { 100L })
        }
        val emptyReservation = assertThrows(IllegalArgumentException::class.java) {
            DownloadStorageSpaceGuard(unknownReservationBytes = 0L, usableSpaceOf = { 100L })
        }

        assertEquals("minimumFreeBytes must not be negative", negativeFree.message)
        assertEquals("unknownReservationBytes must be positive", emptyReservation.message)
    }

    @Test
    fun `reservation owners must be non blank and unique per volume`() {
        val root = tempFolder.root
        val guard = guard()

        val blank = assertThrows(IllegalArgumentException::class.java) { guard.reserve(root, "  ") }
        guard.reserve(root, " owner ")
        val duplicate = assertThrows(IllegalStateException::class.java) { guard.reserve(root, "owner") }

        assertEquals("ownerKey must not be blank", blank.message)
        assertEquals("space reservation owner already exists: owner", duplicate.message)
        assertEquals(1, guard.snapshot(root).ownerCount)
        assertEquals(5L, guard.snapshot(root).reservedBytes)
    }

    @Test
    fun `consumed bytes never drop below zero and stale leases are ignored`() {
        val root = tempFolder.root
        val guard = guard()
        val lease = guard.reserve(root, "owner", expectedAdditionalBytes = 10L)

        lease.consumeReservedBytes(4L)
        assertEquals(6L, guard.snapshot(root).reservedBytes)
        lease.consumeReservedBytes(100L)
        lease.consumeReservedBytes(1L)
        assertEquals(0L, guard.snapshot(root).reservedBytes)
        assertEquals(0L, lease.reservedBytes())

        lease.close()
        val replacement = guard.reserve(root, "owner", expectedAdditionalBytes = 20L)
        lease.consumeReservedBytes(5L)
        assertEquals(20L, guard.snapshot(root).reservedBytes)
        assertEquals(20L, replacement.reservedBytes())

        replacement.close()
        replacement.consumeReservedBytes(5L)
        assertEquals(0L, guard.snapshot(root).reservedBytes)
        assertEquals(0, guard.snapshot(root).ownerCount)
    }

    @Test
    fun `missing roots resolve to the nearest existing directory`() {
        val root = tempFolder.root
        val guard = guard()
        val existingFile = tempFolder.newFile("existing.mp3")
        val rootless = File("missing-download-space-root.mp3")

        assertEquals(root.canonicalPath, guard.snapshot(root).rootPath)
        assertEquals(root.canonicalPath, guard.snapshot(existingFile).rootPath)
        assertEquals(root.canonicalPath, guard.snapshot(File(root, "pending.mp3")).rootPath)
        assertEquals(root.canonicalPath, guard.snapshot(File(root, "a/b/c.mp3")).rootPath)
        assertEquals(rootless.canonicalPath, guard.snapshot(rootless).rootPath)
        assertFalse(rootless.exists())
    }

    @Test
    fun `volume keys fall back to the resolved path when the probe fails or is blank`() {
        val first = tempFolder.newFolder("first")
        val second = tempFolder.newFolder("second")

        val shared = guard(storageVolumeKeyOf = { " shared " })
        shared.reserve(first, "owner", expectedAdditionalBytes = 10L)
        assertEquals(10L, shared.snapshot(second).reservedBytes)

        listOf<(File) -> String>({ "   " }, { throw IOException("stat failed") }).forEach { volumeKeyOf ->
            val perPath = guard(storageVolumeKeyOf = volumeKeyOf)
            perPath.reserve(first, "owner", expectedAdditionalBytes = 10L)
            assertEquals(10L, perPath.snapshot(first).reservedBytes)
            assertEquals(0L, perPath.snapshot(second).reservedBytes)
        }
    }

    @Test
    fun `guarded output validates ranges and skips empty writes`() {
        val root = tempFolder.root
        val guard = guard()
        val output = ByteArrayOutputStream()
        val guarded = guard.guardOutput(output, root, "writer", expectedAdditionalBytes = 0L)
        val bytes = byteArrayOf(1, 2, 3)

        guarded.write(bytes, 0, 0)
        listOf(-1 to 1, 0 to -1, 2 to 2).forEach { (offset, length) ->
            assertThrows(IllegalArgumentException::class.java) { guarded.write(bytes, offset, length) }
        }
        assertEquals(0, output.size())

        guarded.write(bytes, 1, 2)
        assertArrayEquals(byteArrayOf(2, 3), output.toByteArray())
        assertEquals(0L, guard.snapshot(root).reservedBytes)

        guarded.close()
        assertEquals(0, guard.snapshot(root).ownerCount)
    }

    @Test
    fun `no space messages are recognised anywhere in the cause chain`() {
        listOf(
            "write failed: ENOSPC",
            "No space left on device",
            "disk full",
            "Insufficient storage",
            " storage full "
        ).forEach { message ->
            assertEquals(
                message,
                DownloadStorageSpaceFailureKind.PROVIDER_FAILURE,
                classifyDownloadStorageSpaceFailure(IOException(message))
            )
        }
        assertEquals(
            DownloadStorageSpaceFailureKind.PROVIDER_FAILURE,
            classifyDownloadStorageSpaceFailure(RuntimeException("copy failed", IOException("no space left")))
        )
        assertEquals(
            DownloadStorageSpaceFailureKind.PROBE_UNAVAILABLE,
            classifyDownloadStorageSpaceFailure(
                RuntimeException(
                    "wrapper",
                    DownloadStorageSpaceException(
                        rootPath = "/downloads",
                        usableBytes = 0L,
                        reservedBytes = 0L,
                        requestedBytes = 1L,
                        minimumFreeBytes = 0L,
                        usableSpaceKnown = false
                    )
                )
            )
        )
    }

    @Test
    fun `blank missing and unrelated messages are not storage failures`() {
        assertNull(classifyDownloadStorageSpaceFailure(IOException("   ")))
        assertNull(classifyDownloadStorageSpaceFailure(IOException(null as String?)))
        assertNull(classifyDownloadStorageSpaceFailure(IOException("permission denied")))
        assertFalse(containsDownloadStorageSpaceFailure(RuntimeException("timeout", IOException("reset"))))
    }

    private fun guard(
        storageVolumeKeyOf: (File) -> String = { "volume" }
    ): DownloadStorageSpaceGuard {
        return DownloadStorageSpaceGuard(
            minimumFreeBytes = 0L,
            unknownReservationBytes = 5L,
            usableSpaceOf = { 100L },
            storageVolumeKeyOf = storageVolumeKeyOf
        )
    }
}
