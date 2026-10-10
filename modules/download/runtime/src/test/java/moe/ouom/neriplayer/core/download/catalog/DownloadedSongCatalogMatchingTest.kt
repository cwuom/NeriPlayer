package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongCatalogMatchingTest {

    @Test
    fun `catalog delta reports only trimmed stable keys that disappeared`() {
        val retained = downloaded(filePath = "/music/1.mp3", stableKey = "source|1|")
        val removed = downloaded(filePath = "/music/2.mp3", stableKey = " source|2| ")
        val blank = downloaded(filePath = "/music/blank.mp3", stableKey = "   ")
        val unkeyed = downloaded(filePath = "/music/unkeyed.mp3", stableKey = null)
        val added = downloaded(filePath = "/music/3.mp3", stableKey = "source|3|")

        val delta = buildDownloadedSongCatalogDelta(
            previousSongs = listOf(retained, removed, blank, unkeyed),
            currentSongs = listOf(retained, added)
        )

        assertEquals(listOf(added), delta.upserts)
        assertEquals(setOf("source|2|"), delta.removedStableKeys)
        assertTrue(delta.requiresFullPersistence)
        assertFalse(delta.isEmpty)
    }

    @Test
    fun `catalog delta is empty only without upserts and removals`() {
        val song = downloaded(filePath = "/music/1.mp3", stableKey = "source|1|")

        assertTrue(buildDownloadedSongCatalogDelta(listOf(song), listOf(song)).isEmpty)
        assertFalse(DownloadedSongCatalogDelta(emptyList(), setOf("source|1|")).isEmpty)
        assertFalse(DownloadedSongCatalogDelta(listOf(song), emptySet()).isEmpty)
    }

    @Test
    fun `local song falls back to a unique file name match with compatible metadata`() {
        val downloaded = downloaded(filePath = "/old-root/Song.mp3", customName = "  ")
        val index = buildDownloadedSongCatalogIndex(listOf(downloaded))
        val local = localSong(localFileName = "  ", customName = "   ")

        assertSame(downloaded, index.find(local))
        assertTrue(index.contains(local))
        assertNull(index.find(local.copy(name = "Other Song")))
        assertNull(index.find(local.copy(durationMs = 240_000L)))
    }

    @Test
    fun `local song file name match must be unambiguous`() {
        val first = downloaded(filePath = "/old-root/a/Song.mp3")
        val second = downloaded(filePath = "/old-root/b/Song.mp3")
        val index = buildDownloadedSongCatalogIndex(listOf(first, second))

        assertNull(index.find(localSong()))
        assertSame(first, buildDownloadedSongCatalogIndex(listOf(first)).find(localSong()))
    }

    @Test
    fun `verified remote entries match by stable key instead of legacy name fields`() {
        val existing = netease(audioId = "42", filePath = "/music/a.flac")
        val legacy = downloaded(filePath = "/music/legacy.flac", id = 42L)

        assertTrue(matchesDownloadedSongCatalogEntry(existing, netease(audioId = "42", filePath = "/music/b.flac")))
        assertFalse(matchesDownloadedSongCatalogEntry(existing, netease(audioId = "43", filePath = "/music/b.flac")))
        assertFalse(matchesDownloadedSongCatalogEntry(legacy, existing))
        assertTrue(matchesDownloadedSongCatalogEntry(legacy, legacy.copy(filePath = "/music/legacy-copy.flac")))
    }

    @Test
    fun `verified entries without a stable key compare their remote track keys`() {
        val existing = subAudioOnly(subAudioId = "p1", filePath = "/music/a.m4a")
        val legacy = downloaded(filePath = "/music/legacy.m4a", id = 0L)

        assertTrue(matchesDownloadedSongCatalogEntry(existing, subAudioOnly(subAudioId = " p1 ", filePath = "/music/b.m4a")))
        assertFalse(matchesDownloadedSongCatalogEntry(existing, subAudioOnly(subAudioId = "p2", filePath = "/music/b.m4a")))
        assertFalse(matchesDownloadedSongCatalogEntry(legacy, existing))
    }

    @Test
    fun `upsert replaces the verified entry and keeps newest first ordering`() {
        val previous = netease(audioId = "42", filePath = "/music/a.flac").copy(downloadTime = 1L)
        val other = downloaded(filePath = "/music/other.flac", id = 7L).copy(downloadTime = 5L)
        val updated = netease(audioId = "42", filePath = "/music/b.flac").copy(downloadTime = 3L)

        assertEquals(listOf(other, updated), upsertDownloadedSongCatalog(listOf(previous, other), updated))
    }

    private fun downloaded(
        filePath: String,
        stableKey: String? = null,
        id: Long = 1L,
        customName: String? = null
    ) = DownloadedSong(
        id = id,
        name = "Song",
        artist = "Artist",
        album = "Album",
        filePath = filePath,
        fileSize = 4L,
        downloadTime = 1L,
        durationMs = 180_000L,
        stableKey = stableKey,
        customName = customName
    )

    private fun netease(audioId: String, filePath: String) = downloaded(filePath = filePath, id = audioId.toLong())
        .copy(
            album = "netease",
            stableKey = "$audioId|netease|",
            sourceIdentityAlbum = "netease",
            sourceChannelId = "netease",
            sourceAudioId = audioId
        )

    private fun subAudioOnly(subAudioId: String, filePath: String) = downloaded(filePath = filePath, id = 0L)
        .copy(sourceChannelId = "bilibili", sourceSubAudioId = subAudioId)

    private fun localSong(localFileName: String? = null, customName: String? = null) = SongItem(
        id = 99L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 181_000L,
        coverUrl = null,
        customName = customName,
        localFileName = localFileName,
        localFilePath = "/storage/Music/Song.mp3"
    )
}
