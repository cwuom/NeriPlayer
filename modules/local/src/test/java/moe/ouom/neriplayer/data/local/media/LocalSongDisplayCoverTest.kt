package moe.ouom.neriplayer.data.local.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.concurrent.thread
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class LocalSongDisplayCoverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val covers = FakeCoverAccess()

    @Before
    fun bindMediaHost() {
        LocalMediaHostAccess.bind(
            downloads = mock(LocalMediaDownloadAccess::class.java),
            covers = covers,
            crashLogs = CrashLogCleanup { false }
        )
    }

    @Test
    fun `custom covers win before the host is consulted`() {
        val song = localSong().copy(customCoverUrl = "content://photos/picked.jpg")

        assertEquals("content://photos/picked.jpg", song.displayCoverUrl(context))
        assertEquals(0, covers.peekCount)
    }

    @Test
    fun `local songs on the main thread use a peeked cover but never a shared album thumbnail`() {
        covers.peeked = "file:///covers/a.jpg"
        assertEquals("file:///covers/a.jpg", localSong().displayCoverUrl(context))

        covers.peeked = "content://media/external/audio/albumart/3"
        assertNull(localSong().displayCoverUrl(context))
        assertEquals(2, covers.peekCount)
    }

    @Test
    fun `local songs off the main thread prefer a peeked cover before probing the file`() {
        covers.peeked = "file:///covers/a.jpg"
        assertEquals("file:///covers/a.jpg", offMainThread { localSong().displayCoverUrl(context) })

        covers.peeked = null
        assertNull(offMainThread { localSong().displayCoverUrl(context) })
        assertEquals(2, covers.peekCount)
    }

    @Test
    fun `disabling the metadata fallback keeps the stored cover without consulting the host`() {
        val stale = localSong().copy(coverUrl = "content://media/external/audio/albumart/3")
        val kept = localSong().copy(coverUrl = "neri-cover://kept")

        assertNull(stale.displayCoverUrl(context, resolveLocalMetadataFallback = false))
        assertEquals("neri-cover://kept", kept.displayCoverUrl(context))
        assertEquals(0, covers.peekCount)
        assertEquals(emptyList<Boolean>(), covers.lookups)
    }

    @Test
    fun `remote songs ask the host for a cover without the local media fallback`() {
        covers.resolved = "https://img.example.com/remote.jpg"

        assertEquals("https://img.example.com/remote.jpg", remoteSong().displayCoverUrl(context))
        assertEquals(listOf(false), covers.lookups)
    }

    private fun <T> offMainThread(block: () -> T): T {
        var result: Result<T>? = null
        thread { result = runCatching(block) }.join()
        return checkNotNull(result).getOrThrow()
    }

    private fun localSong() = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        localFilePath = tempFolder.root.resolve("missing.mp3").absolutePath
    )

    private fun remoteSong() = SongItem(
        id = 2L,
        name = "Remote",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null
    )

    private class FakeCoverAccess : LocalMediaCoverAccess {
        var peeked: String? = null
        var resolved: String? = null
        var peekCount = 0
        val lookups = mutableListOf<Boolean>()

        override fun peekLocalCoverUri(song: SongItem): String? {
            peekCount++
            return peeked
        }

        override fun getLocalCoverUri(
            context: Context,
            song: SongItem,
            resolveLocalMediaFallback: Boolean
        ): String? {
            lookups += resolveLocalMediaFallback
            return resolved
        }
    }
}
