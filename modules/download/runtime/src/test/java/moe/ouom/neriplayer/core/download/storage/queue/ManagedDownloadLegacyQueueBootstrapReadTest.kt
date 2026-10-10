package moe.ouom.neriplayer.core.download.storage.queue

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.PendingDownloadQueueEntry
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadLegacyQueueBootstrapReadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `legacy pending queue keeps order and network routing for bootstrap`() {
        val first = song(id = 101L, name = "First")
        val second = song(id = 102L, name = "Second")
        val entries = listOf(
            PendingDownloadQueueEntry(
                stableKey = first.stableKey(),
                song = first,
                order = 0,
                queuedAtMs = 10L,
                operationId = "op-first",
                requiresWifiNetwork = false
            ),
            PendingDownloadQueueEntry(
                stableKey = second.stableKey(),
                song = second,
                order = 1,
                queuedAtMs = 11L
            )
        )
        val queueFile = tempFolder.newFile("pending_queue.json").apply {
            writeText(
                ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
                    entries = entries,
                    updatedAtMs = 12L
                )
            )
        }

        assertEquals(entries, ManagedDownloadQueueStore.readPendingDownloadQueueFile(queueFile))
    }

    @Test
    fun `legacy cancellation file restores every cancelled song key`() {
        val keys = setOf(song(id = 201L, name = "First").stableKey(), song(id = 202L, name = "Second").stableKey())
        val keysFile = tempFolder.newFile("cancelled_keys.json").apply {
            writeText(
                ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
                    songKeys = keys,
                    updatedAtMs = 20L
                )
            )
        }

        assertEquals(keys, ManagedDownloadQueueStore.readCancelledDownloadKeysFile(keysFile))
    }

    @Test
    fun `absent blank or unreadable legacy files contribute no bootstrap state`() {
        val missing = File(tempFolder.root, "missing.json")
        val directory = tempFolder.newFolder("queue.json")
        val blank = tempFolder.newFile("blank.json").apply { writeText(" \n\t") }
        val vanished = VanishedFile(File(tempFolder.root, "vanished.json"))

        listOf(missing, directory, blank, vanished).forEach { legacyFile ->
            assertNull(legacyFile.name, ManagedDownloadQueueStore.readPendingDownloadQueueFile(legacyFile))
            assertNull(legacyFile.name, ManagedDownloadQueueStore.readCancelledDownloadKeysFile(legacyFile))
        }
    }

    @Test
    fun `corrupt legacy payloads are rejected instead of becoming runtime state`() {
        val corrupt = tempFolder.newFile("corrupt.json").apply { writeText("{\"entries\": [") }

        assertNull(ManagedDownloadQueueStore.readPendingDownloadQueueFile(corrupt))
        assertNull(ManagedDownloadQueueStore.readCancelledDownloadKeysFile(corrupt))
    }

    private fun song(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 180_000L,
            coverUrl = null
        )
    }

    /** Reports itself as a file although it is deleted before it can be read. */
    private class VanishedFile(source: File) : File(source.path) {
        override fun isFile(): Boolean = true
    }
}
