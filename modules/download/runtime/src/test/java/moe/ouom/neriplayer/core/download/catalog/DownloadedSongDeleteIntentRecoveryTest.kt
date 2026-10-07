package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DownloadedSongDeleteIntentRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val store = PersistentDownloadedSongDeleteIntentStore

    @Test
    fun `intent resolves catalog songs by deletion identity or trimmed stable key`() {
        val byFilePath = downloadedSong(1L, "/library/a.mp3", stableKey = null)
        val byStableKey = downloadedSong(2L, "/library/b.mp3", stableKey = " key-b ")
        val withoutKey = downloadedSong(3L, "/library/c.mp3", stableKey = null)
        val blankKey = downloadedSong(4L, "/library/d.mp3", stableKey = "  ")
        val foreignKey = downloadedSong(5L, "/library/e.mp3", stableKey = "key-e")
        val byMediaUri = downloadedSong(6L, "/library/f.mp3", stableKey = null, mediaUri = "content://media/6")
        val intent = DownloadedSongDeleteIntent(
            rootKey = "root",
            requestedAtMs = 1L,
            targets = listOf(
                DownloadedSongDeleteTarget("/library/a.mp3", stableKey = null),
                DownloadedSongDeleteTarget("/library/moved-b.mp3", stableKey = "key-b"),
                DownloadedSongDeleteTarget("content://media/6", stableKey = null)
            )
        )

        assertEquals(
            listOf(byFilePath, byStableKey, byMediaUri),
            intent.resolveSongs(listOf(byFilePath, byStableKey, withoutKey, blankKey, foreignKey, byMediaUri))
        )
        assertTrue(intent.copy(targets = emptyList()).resolveSongs(listOf(byFilePath)).isEmpty())
        assertTrue(intent.resolveSongs(emptyList()).isEmpty())
    }

    @Test
    fun `archiving requires a pending intent and a reachable files directory`() {
        val context = testContext()

        assertFalse(store.archiveUnconfirmed(context, 1L))
        assertFalse(store.hasUnconfirmedForEpoch(context, 1L))
        assertFalse(store.archiveUnconfirmed(mock(Context::class.java), 1L))
    }

    @Test
    fun `clear succeeds only when the intent file is confirmed gone`() {
        val context = testContext()

        assertTrue(store.clear(context))
        assertTrue(store.begin(context, "root", listOf(downloadedSong(1L, "/library/a.mp3", "key"))))
        assertTrue(store.clear(context))
        assertFalse(store.hasPending(context))

        val undeletable = File(temporaryFolder.root, INTENT_FILE_NAME)
        assertTrue(undeletable.mkdir())
        File(undeletable, "child").writeText("keep")
        assertFalse(store.clear(context))
        assertTrue(undeletable.isDirectory)
        assertFalse(store.clear(mock(Context::class.java)))
    }

    @Test
    fun `merging references that are already owned leaves the intent file untouched`() {
        val context = testContext()
        assertTrue(store.begin(context, "root", listOf(downloadedSong(1L, "/library/a.mp3", "key"))))
        assertTrue(store.mergeOwnedReferences(context, "root", setOf("cover")))
        val intentFile = File(temporaryFolder.root, INTENT_FILE_NAME)
        val persisted = intentFile.readText()

        assertTrue(store.mergeOwnedReferences(context, "root", setOf("cover")))
        assertTrue(store.mergeOwnedReferences(context, "root", emptySet()))

        assertEquals(persisted, intentFile.readText())
        assertEquals(setOf("cover"), store.read(context)?.ownedReferences)
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
        stableKey: String?,
        mediaUri: String? = null
    ): DownloadedSong {
        return DownloadedSong(
            id = id,
            name = "song-$id",
            artist = "artist",
            album = "album",
            filePath = filePath,
            fileSize = 10L,
            downloadTime = id,
            mediaUri = mediaUri,
            stableKey = stableKey
        )
    }

    private companion object {
        const val INTENT_FILE_NAME = "downloaded_song_delete_intent_v1.json"
    }
}
