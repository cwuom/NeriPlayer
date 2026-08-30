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
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.execution.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore

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
    }

    private fun testContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return context
    }

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
