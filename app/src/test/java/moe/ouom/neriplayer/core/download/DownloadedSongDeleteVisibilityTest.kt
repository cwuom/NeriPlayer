package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongDeleteVisibilityTest {
    @Test
    fun `begin immediately hides targeted songs`() {
        val first = downloadedSong(id = 1L, name = "first")
        val second = downloadedSong(id = 2L, name = "second")
        val visibility = DownloadedSongDeleteVisibility()

        visibility.begin(listOf(first))

        assertEquals(listOf(second), visibility.filterVisible(listOf(first, second)))
    }

    @Test
    fun `finishing current token makes a failed deletion visible again`() {
        val song = downloadedSong(id = 1L, name = "failed")
        val visibility = DownloadedSongDeleteVisibility()
        val token = visibility.begin(listOf(song))

        visibility.finish(token)

        assertEquals(listOf(song), visibility.filterVisible(listOf(song)))
        assertTrue(!visibility.hasActiveDeletions())
    }

    @Test
    fun `older overlapping deletion cannot reveal newer tombstone`() {
        val song = downloadedSong(id = 1L, name = "same")
        val visibility = DownloadedSongDeleteVisibility()
        val first = visibility.begin(listOf(song))
        val second = visibility.begin(listOf(song))

        visibility.finish(first)

        assertTrue(!visibility.owns(first, song))
        assertTrue(visibility.owns(second, song))
        assertTrue(visibility.filterVisible(listOf(song)).isEmpty())

        visibility.finish(second)

        assertEquals(listOf(song), visibility.filterVisible(listOf(song)))
    }

    @Test
    fun `newer overlapping deletion retains the first visible baseline`() {
        val original = downloadedSong(id = 1L, name = "original")
        val staleCopy = original.copy(name = "stale")
        val visibility = DownloadedSongDeleteVisibility()

        visibility.begin(listOf(original))
        val newer = visibility.begin(listOf(staleCopy))

        assertEquals(
            original,
            newer.baselineSongsByIdentity.getValue(original.deletionIdentity())
        )
    }

    @Test
    fun `physical deletion from older overlap prevents newer failure from restoring it`() {
        val song = downloadedSong(id = 1L, name = "same")
        val visibility = DownloadedSongDeleteVisibility()
        val older = visibility.begin(listOf(song))
        val newer = visibility.begin(listOf(song))

        visibility.recordDeleted(older, listOf(song))
        visibility.finish(older)

        assertTrue(visibility.wasPhysicallyDeleted(newer, song))
        visibility.finish(newer)
        assertTrue(!visibility.hasActiveDeletions())
    }

    @Test
    fun `equivalent whitespace padded references use one deletion identity`() {
        val canonical = downloadedSong(id = 1L, name = "same").copy(
            mediaUri = "content://downloads/audio/1.mp3"
        )
        val padded = canonical.copy(mediaUri = " content://downloads/audio/1.mp3 ")
        val visibility = DownloadedSongDeleteVisibility()
        val token = visibility.begin(listOf(padded))

        assertTrue(visibility.filterVisible(listOf(canonical)).isEmpty())
        assertTrue(visibility.owns(token, canonical))
    }

    private fun downloadedSong(id: Long, name: String): DownloadedSong {
        return DownloadedSong(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            filePath = "content://downloads/audio/$id.mp3",
            fileSize = 1L,
            downloadTime = id,
            mediaUri = "content://downloads/audio/$id.mp3",
            stableKey = "$id|netease|"
        )
    }
}
