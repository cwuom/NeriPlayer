package moe.ouom.neriplayer.core.download.storage.reference

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadReferenceLookupTest {
    @Test
    fun `only an explicit missing provider failure can mark reference missing`() {
        assertTrue(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("permission revoked")
                )
            )
        )
    }

    @Test
    fun `file not found is missing while an arbitrary io failure is not`() {
        assertTrue(
            ManagedDownloadReferenceLookup.isMissingFailure(
                FileNotFoundException("missing document")
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.isMissingFailure(
                IllegalStateException("provider returned an empty cursor")
            )
        )
    }

    @Test
    fun `permission file not found remains permission lost`() {
        val error = FileNotFoundException("provider permission denied")
        val result = ManagedDownloadReferenceLookup.classifyFailure(error)

        assertTrue(result is ManagedDownloadReferenceLookup.Result.PermissionLost)
        assertEquals(error, (result as ManagedDownloadReferenceLookup.Result.PermissionLost)
            .cause.cause)
    }

    @Test
    fun `arbitrary file not found remains provider failure`() {
        val error = FileNotFoundException("provider temporarily unavailable")
        val result = ManagedDownloadReferenceLookup.classifyFailure(error)

        assertTrue(result is ManagedDownloadReferenceLookup.Result.ProviderFailure)
        assertEquals(error, (result as ManagedDownloadReferenceLookup.Result.ProviderFailure)
            .cause)
    }

    @Test
    fun `reference lookup delegates provider inspection to the canonical io boundary`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/reference/ManagedDownloadReferenceLookup.kt"
        ).readText()

        assertFalse(source.contains("contentResolver.query"))
        assertFalse(source.contains("openFileDescriptor"))
        assertTrue(source.contains("ManagedDownloadReferenceIo.inspect"))
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
