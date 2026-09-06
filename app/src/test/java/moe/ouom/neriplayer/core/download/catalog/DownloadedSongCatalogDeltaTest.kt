package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongCatalogDeltaTest {
    @Test
    fun `delta index matches a full rebuild after replacement and removal`() {
        val first = song(
            stableKey = "source|1|",
            filePath = "/music/first.mp3",
            name = "first"
        )
        val second = song(
            stableKey = "source|2|",
            filePath = "/music/second.mp3",
            name = "second"
        )
        val replacement = first.copy(
            filePath = "/music/first-renamed.mp3",
            name = "first renamed",
            downloadTime = 3L
        )
        val previous = listOf(second, first)
        val current = listOf(replacement)
        val delta = buildDownloadedSongCatalogDelta(previous, current)

        assertEquals(listOf(replacement), delta.upserts)
        assertEquals(setOf(second.stableKey), delta.removedStableKeys)
        assertTrue(!delta.requiresFullPersistence)

        val incrementallyBuilt = applyDownloadedSongCatalogDelta(
            previousIndex = buildDownloadedSongCatalogIndex(previous),
            previousSongs = previous,
            currentSongs = current
        )
        assertEquals(buildDownloadedSongCatalogIndex(current), incrementallyBuilt)
    }

    @Test
    fun `unkeyed entries request full persistence because Room preview has no identity`() {
        val unkeyed = song(
            stableKey = null,
            filePath = "/music/unkeyed.mp3",
            name = "unkeyed"
        )

        val delta = buildDownloadedSongCatalogDelta(emptyList(), listOf(unkeyed))

        assertTrue(delta.requiresFullPersistence)
    }

    private fun song(
        stableKey: String?,
        filePath: String,
        name: String,
        downloadTime: Long = 1L
    ): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = name,
            artist = "artist",
            album = "album",
            filePath = filePath,
            fileSize = 100L,
            downloadTime = downloadTime,
            stableKey = stableKey
        )
    }
}
