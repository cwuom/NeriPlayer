package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.Context
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedMetadataReadUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class ManagedDownloadStorageMetadataReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storage = ManagedDownloadStorage
    private val context = mock(Context::class.java)

    @Test
    fun `pending artifacts select metadata only for unfinished audio`() {
        val pendingAudio = entry("Song.mp3.npdl_pending.op-1.pending")
        val blankPendingAudio = entry("  .npdl_pending.op-3.pending")
        val pendingMetadata = entry("Other.mp3.npmeta.pending.json")
        val songMetadata = entry("Song.mp3.npmeta.json")
        val unrelatedMetadata = entry("Third.mp3.npmeta.json")
        val finishedAudio = entry("Third.mp3")
        val pendingDirectory = entry("Fourth.mp3.npdl_pending.op-2.pending", isDirectory = true)
        val fourthMetadata = entry("Fourth.mp3.npmeta.json")

        assertEquals(
            listOf(pendingMetadata, songMetadata),
            storage.metadataEntriesForPendingArtifacts(
                listOf(
                    pendingAudio,
                    blankPendingAudio,
                    pendingMetadata,
                    songMetadata,
                    unrelatedMetadata,
                    finishedAudio,
                    pendingDirectory,
                    fourthMetadata
                )
            )
        )
        assertTrue(
            storage.metadataEntriesForPendingArtifacts(listOf(songMetadata, unrelatedMetadata, finishedAudio)).isEmpty()
        )
    }

    @Test
    fun `entry batch parses readable metadata and leaves malformed or missing files empty`() = runTest {
        val valid = metadataFile("Song.mp3.npmeta.json", """{"stableKey":"1|netease|","name":"Song"}""")
        val malformed = metadataFile("Broken.mp3.npmeta.json", "{broken")
        val missing = entry("Missing.mp3.npmeta.json", File(temporaryFolder.root, "Missing.mp3.npmeta.json").absolutePath)

        val parsed = storage.parseDownloadedAudioMetadataEntriesBatch(context, listOf(valid, malformed, missing))

        assertEquals(listOf(valid, malformed, missing), parsed.map { it.first })
        assertEquals("1|netease|", parsed[0].second?.stableKey)
        assertEquals("Song", parsed[0].second?.name)
        assertNull(parsed[1].second)
        assertNull(parsed[2].second)
    }

    @Test
    fun `audio name batch can require every metadata reference to be readable`() {
        val valid = metadataFile("Song.mp3.npmeta.json", """{"stableKey":"1|netease|","name":"Song"}""")
        val missing = entry("Missing.mp3.npmeta.json", File(temporaryFolder.root, "Missing.mp3.npmeta.json").absolutePath)
        val unsupported = entry("Orphan.mp3.npmeta.json", "Orphan.mp3.npmeta.json")

        assertTrue(storage.parseDownloadedAudioMetadataBatch(context, emptyList(), requireAvailable = true).isEmpty())
        val byName = storage.parseDownloadedAudioMetadataBatch(
            context,
            listOf("Song.mp3" to valid, "Missing.mp3" to missing),
            requireAvailable = true
        )
        assertEquals(setOf("Song.mp3", "Missing.mp3"), byName.keys)
        assertEquals("Song", byName["Song.mp3"]?.name)
        assertNull(byName["Missing.mp3"])

        assertNull(storage.parseDownloadedAudioMetadataBatch(context, listOf("Orphan.mp3" to unsupported))["Orphan.mp3"])
        val failure = assertThrows(ManagedMetadataReadUnavailableException::class.java) {
            storage.parseDownloadedAudioMetadataBatch(
                context,
                listOf("Orphan.mp3" to unsupported),
                requireAvailable = true
            )
        }
        assertEquals(setOf("Orphan.mp3.npmeta.json"), failure.references)
    }

    private fun metadataFile(name: String, content: String): StoredEntry {
        val file = File(temporaryFolder.root, name).apply { writeText(content) }
        return entry(name, file.absolutePath)
    }

    private fun entry(
        name: String,
        reference: String = "/music/$name",
        isDirectory: Boolean = false
    ) = StoredEntry(
        name = name,
        reference = reference,
        mediaUri = reference,
        localFilePath = reference.takeIf { it.startsWith("/") },
        sizeBytes = 1L,
        lastModifiedMs = 1L,
        isDirectory = isDirectory
    )
}
