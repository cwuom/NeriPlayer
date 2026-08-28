package moe.ouom.neriplayer.core.download.storage.migration

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationTargetResolverPerformanceTest {
    @Test
    fun `ordinary entries do not probe every target name for metadata alternates`() {
        val parent = Files.createTempDirectory("neriplayer-target-resolver").toFile()
        try {
            val reads = AtomicInteger()
            val source = storedEntry(
                name = "song.mp3",
                reference = "/source/song.mp3"
            )
            val targetNames = (0 until 2_000)
                .map { index -> "unrelated-$index.mp3" }
                .toSet()

            val resolved = ManagedDownloadMigrationTargetResolver.resolveFileTarget(
                parent = parent,
                displayName = source.name,
                sourceEntry = source,
                targetNames = targetNames,
                targetEntry = null,
                readExistingEntry = {
                    reads.incrementAndGet()
                    error("ordinary audio must not read metadata candidates")
                },
                reserveName = { name -> name },
                onReuseMetadata = {},
                onReuseFile = {}
            )

            assertEquals(0, reads.get())
            assertTrue(resolved.createdNew)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `metadata alternate lookup reads only names for the same audio`() {
        val parent = Files.createTempDirectory("neriplayer-target-resolver").toFile()
        try {
            val audioName = "song.mp3"
            val source = storedEntry(
                name = "$audioName.npmeta.json",
                reference = "/source/$audioName.npmeta.json"
            )
            val alternateName = "$audioName.npmeta (2).json"
            val alternate = storedEntry(
                name = alternateName,
                reference = "/target/$alternateName"
            )
            val unrelatedNames = (0 until 2_000)
                .map { index -> "other-$index.mp3.npmeta.json" }
            val readsByName = linkedMapOf<String, Int>()
            val resolved = ManagedDownloadMigrationTargetResolver.resolveFileTarget(
                parent = parent,
                displayName = source.name,
                sourceEntry = source,
                targetNames = (unrelatedNames + alternateName).toSet(),
                targetEntry = null,
                readExistingEntry = { file: File ->
                    readsByName[file.name] = (readsByName[file.name] ?: 0) + 1
                    alternate.takeIf { file.name == alternateName }
                },
                reserveName = { name -> error("matching metadata must be reused: $name") },
                onReuseMetadata = {},
                onReuseFile = {}
            )

            assertFalse(resolved.createdNew)
            assertEquals(alternateName, resolved.entry.name)
            assertEquals(mapOf(alternateName to 1), readsByName)
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun storedEntry(name: String, reference: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = reference,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }
}
