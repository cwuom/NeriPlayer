package moe.ouom.neriplayer.core.download.resource

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import moe.ouom.neriplayer.common.units.MEBIBYTE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadStorageSpaceGuardProbeThrottleTest {

    @Test
    fun `ample free space is probed once per window instead of on every write`() = withRoot { root ->
        val volume = FakeVolume(usableBytes = 4_096 * MEBIBYTE_BYTES)
        val guarded = volume.guard().guardOutput(ByteArrayOutputStream(), root, "stream")

        repeat(200) { guarded.write(ByteArray(64 * 1024)) }
        assertEquals(1, volume.probes)

        volume.nowNanos += DownloadStorageSpaceGuard.SPARE_PROBE_WINDOW_NANOS
        guarded.write(ByteArray(64 * 1024))
        assertEquals(2, volume.probes)
        guarded.close()
    }

    @Test
    fun `space close to the threshold is probed before every write`() = withRoot { root ->
        val volume = FakeVolume(usableBytes = 48 * MEBIBYTE_BYTES)
        val guarded = volume.guard().guardOutput(ByteArrayOutputStream(), root, "stream")

        repeat(5) { guarded.write(ByteArray(1024)) }

        assertEquals(6, volume.probes)
        guarded.close()
    }

    @Test
    fun `space taken by other apps is detected once the window ends`() = withRoot { root ->
        val volume = FakeVolume(usableBytes = 4_096 * MEBIBYTE_BYTES)
        val guarded = volume.guard().guardOutput(ByteArrayOutputStream(), root, "stream")
        guarded.write(ByteArray(1024))

        volume.usableBytes = MEBIBYTE_BYTES
        volume.nowNanos += DownloadStorageSpaceGuard.SPARE_PROBE_WINDOW_NANOS

        assertThrows(DownloadStorageSpaceException::class.java) {
            guarded.write(ByteArray(1024))
        }
        guarded.close()
    }

    @Test
    fun `a reservation larger than the remembered spare space probes again`() = withRoot { root ->
        val volume = FakeVolume(usableBytes = 200 * MEBIBYTE_BYTES)
        val guard = volume.guard()
        val first = guard.reserve(root, "first", expectedAdditionalBytes = MEBIBYTE_BYTES)

        assertThrows(DownloadStorageSpaceException::class.java) {
            guard.reserve(root, "second", expectedAdditionalBytes = 190 * MEBIBYTE_BYTES)
        }
        assertEquals(2, volume.probes)
        first.close()
    }

    private class FakeVolume(var usableBytes: Long) {
        var probes = 0
        var nowNanos = 1_000L

        fun guard() = DownloadStorageSpaceGuard(
            usableSpaceOf = {
                probes++
                usableBytes
            },
            nanoTime = { nowNanos }
        )
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("download-space-throttle").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
