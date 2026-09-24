package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore

class DownloadedSongDeleteIntentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `intent round trips stable deletion identities and resolves catalog`() {
        val context = testContext()
        val first = downloadedSong(
            id = 1L,
            filePath = "content://downloads/audio/first.mp3",
            stableKey = "1|netease|"
        )
        val second = downloadedSong(
            id = 2L,
            filePath = "/private/second.mp3",
            stableKey = null
        )

        assertTrue(
            PersistentDownloadedSongDeleteIntentStore.begin(
                context = context,
                rootKey = "tree:primary%3ANeriPlayer",
                songs = listOf(first, second, first)
            )
        )
        val intent = PersistentDownloadedSongDeleteIntentStore.read(context)

        requireNotNull(intent)
        assertEquals("tree:primary%3ANeriPlayer", intent.rootKey)
        assertEquals(2, intent.targets.size)
        assertEquals(
            listOf(second, first),
            intent.resolveSongs(listOf(second, first))
        )
        assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.clear(context))
        assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
    }

    @Test
    fun `malformed intent is ignored without deleting the recovery file`() {
        val context = testContext()
        File(context.filesDir, "downloaded_song_delete_intent_v1.json")
            .writeText("{not-json")

        assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        assertEquals(null, PersistentDownloadedSongDeleteIntentStore.read(context))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
    }

    @Test
    fun `pending full delete intent blocks scheduling after preference fence release`() {
        val context = testContext()
        assertTrue(
            PersistentDownloadedSongDeleteIntentStore.begin(
                context = context,
                rootKey = "tree:primary%3ANeriPlayer",
                songs = listOf(
                    downloadedSong(
                        id = 3L,
                        filePath = "/private/third.mp3",
                        stableKey = "3|netease|"
                    )
                )
            )
        )

        assertTrue(PersistentDownloadClearFenceStore.isActive(context))
        assertEquals(
            DownloadClearPurpose.FULL_LIBRARY_DELETE,
            PersistentDownloadClearFenceStore.activePurpose(context)
        )
        assertTrue(
            PersistentDownloadClearFenceStore.withSchedulingPermit(
                context = context,
                onFenceActive = { true },
                schedule = { false }
            )
        )
        assertTrue(PersistentDownloadedSongDeleteIntentStore.archiveUnconfirmed(context, 1L))
        assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        assertFalse(
            PersistentDownloadClearFenceStore.withSchedulingPermit(
                context = context,
                onFenceActive = { true },
                schedule = { false }
            )
        )
    }

    private fun testContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return context
    }

    @Test
    fun `optional owned references extend atomically without replacing targets or timestamp`() {
        val context = testContext()
        assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, "root", listOf(
            downloadedSong(1L, "/library/a.mp3", "key")
        )))
        val before = requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context))
        assertTrue(before.ownedReferences.isEmpty())
        assertTrue(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, "root", setOf("opaque-cover")))
        assertFalse(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, "other", setOf("foreign")))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, "root", setOf("opaque-lyric")))
        val after = requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context))
        assertEquals(before.targets, after.targets)
        assertEquals(before.requestedAtMs, after.requestedAtMs)
        assertEquals(setOf("opaque-cover", "opaque-lyric"), after.ownedReferences)
        val file = File(context.filesDir, "downloaded_song_delete_intent_v1.json")
        file.writeText(file.readText().replace("\"root\"", "\"other\""))
        assertFalse(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, "root", setOf("new")))
    }

    @Test
    fun `unreadable intent refuses reference expansion`() {
        val context = testContext()
        File(context.filesDir, "downloaded_song_delete_intent_v1.json").mkdir()
        assertFalse(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, "root", setOf("owned")))
    }

    @Test
    fun `unconfirmed deletion keeps its evidence without blocking a later download`() {
        val context = testContext()
        assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, "root", listOf(
            downloadedSong(1L, "/library/a.mp3", "key")
        )))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(
            context, "root", setOf("/library/Lyrics/a.lrc")
        ))

        assertTrue(PersistentDownloadedSongDeleteIntentStore.archiveUnconfirmed(context, 1L))

        assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.hasUnconfirmedForEpoch(context, 1L))
        assertFalse(PersistentDownloadedSongDeleteIntentStore.hasUnconfirmedForEpoch(context, 2L))
        assertEquals(null, PersistentDownloadedSongDeleteIntentStore.read(context))
        assertTrue(unconfirmedReports(context).single()
            .readText().contains("/library/Lyrics/a.lrc"))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, "root", listOf(
            downloadedSong(2L, "/library/b.mp3", "other")
        )))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.archiveUnconfirmed(context, 2L))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.hasUnconfirmedForEpoch(context, 2L))
        assertEquals(2, unconfirmedReports(context).size)
    }

    private fun unconfirmedReports(context: Context): List<File> =
        context.filesDir.listFiles { _, name ->
            name.startsWith("downloaded_song_delete_unconfirmed_v1_") && name.endsWith(".json")
        }?.toList().orEmpty()

    private fun downloadedSong(
        id: Long,
        filePath: String,
        stableKey: String?
    ): DownloadedSong {
        return DownloadedSong(
            id = id,
            name = "song-$id",
            artist = "artist",
            album = "album",
            filePath = filePath,
            fileSize = 10L,
            downloadTime = id,
            stableKey = stableKey
        )
    }
}
