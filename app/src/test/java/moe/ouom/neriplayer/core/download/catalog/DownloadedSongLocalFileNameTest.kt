package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.localFileNameFromFileReference
import moe.ouom.neriplayer.core.download.model.resolvedLocalFileName
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedSongLocalFileNameTest {
    @Test
    fun `complete backup retains literal display name without changing identity`() {
        val song = song().copy(localFileName = "literal%20+colon:name.m4a")
        val restored = requireNotNull(deserializeDownloadedSongsCatalog(
            serializeDownloadedSongsCatalog("root", listOf(song), includeOriginalLyrics = true),
            "root", includeOriginalLyrics = true
        )).single()
        assertEquals(song, restored)
        assertEquals(song.localFileName, restored.toPlaybackSongItem().localFileName)
        assertEquals(song.toPlaybackSongItem().identity(), song.copy(localFileName = "renamed.m4a")
            .toPlaybackSongItem().identity())
        assertEquals(downloadedSongCatalogEntryKey(song), downloadedSongCatalogEntryKey(
            song.copy(localFileName = "renamed.m4a")
        ))
    }

    @Test
    fun `old backup with no filename does not turn opaque id into a name`() {
        val json = JSONObject(serializeDownloadedSongsCatalog("root", listOf(song())))
        json.getJSONArray("songs").getJSONObject(0).remove("localFileName")
        val restored = requireNotNull(deserializeDownloadedSongsCatalog(json.toString(), "root")).single()
        assertNull(restored.localFileName)
        assertNull(restored.resolvedLocalFileName())
        assertNull(restored.toPlaybackSongItem().localFileName)
    }

    @Test
    fun `private filenames keep literal characters and file URIs decode once`() {
        assertEquals("literal%20+colon:name.m4a", localFileNameFromFileReference(
            "/music/literal%20+colon:name.m4a"
        ))
        assertEquals("space and+literal%20.m4a", localFileNameFromFileReference(
            "file:///music/space%20and%2Bliteral%2520.m4a"
        ))
        assertNull(localFileNameFromFileReference("content://provider/document/song.m4a"))
        assertNull(localFileNameFromFileReference("https://server/song.m4a"))
        assertNull(localFileNameFromFileReference("file:///%invalid"))
    }

    @Test
    fun `filename index uses explicit display name and never content id`() {
        val known = song().copy(localFileName = "literal%20+colon:name.m4a")
        val index = buildDownloadedSongCatalogIndex(listOf(known))
        assertEquals(listOf(known), index.songsByLocalFileName["literal%20+colon:name.m4a"])
        assertFalse(index.songsByLocalFileName.containsKey("opaque%2fnode"))
        assertEquals(emptyMap<String, List<DownloadedSong>>(),
            buildDownloadedSongCatalogIndex(listOf(song())).songsByLocalFileName)
    }

    @Test
    fun `preview merge replaces old name when locator changes`() {
        val backup = song().copy(localFileName = "old.m4a")
        val preview = backup.copy(filePath = NEW_REFERENCE, mediaUri = NEW_REFERENCE, localFileName = "new.m4a")
        assertEquals("new.m4a", mergeCatalogBackupWithPreviews(listOf(backup), listOf(preview))
            .single().localFileName)
        assertNull(mergeCatalogBackupWithPreviews(listOf(backup), listOf(preview.copy(localFileName = null)))
            .single().resolvedLocalFileName())
    }

    @Test
    fun `preview with same locator and unknown name keeps full backup name`() {
        val backup = song().copy(localFileName = "old.m4a")
        assertEquals("old.m4a", mergeCatalogBackupWithPreviews(listOf(backup), listOf(song()))
            .single().localFileName)
    }

    @Test
    fun `private preview clears obsolete backup content URI`() {
        val backup = song().copy(localFileName = "old.m4a")
        val preview = backup.copy(filePath = "/music/new.m4a", mediaUri = null, localFileName = null)
        val merged = mergeCatalogBackupWithPreviews(listOf(backup), listOf(preview)).single()
        assertEquals("/music/new.m4a", merged.filePath)
        assertNull(merged.mediaUri)
        assertEquals("new.m4a", merged.resolvedLocalFileName())
        assertEquals("/music/new.m4a", merged.toPlaybackSongItem().mediaUri)
    }

    @Test
    fun `content preview replaces old private locator and clears unknown name`() {
        val backup = song().copy(filePath = "/music/old.m4a", mediaUri = null, localFileName = "old.m4a")
        val merged = mergeCatalogBackupWithPreviews(listOf(backup), listOf(song())).single()
        assertEquals(OLD_REFERENCE, merged.filePath)
        assertEquals(OLD_REFERENCE, merged.mediaUri)
        assertNull(merged.resolvedLocalFileName())
    }

    @Test
    fun `metadata projection replaces name alongside changed local reference`() {
        val existing = song().copy(localFileName = "old.m4a")
        val update = localSong().copy(mediaUri = NEW_REFERENCE, localFileName = "new.m4a")
        val projected = projectDownloadedSongMetadata(existing, update)
        assertEquals(NEW_REFERENCE, projected.mediaUri)
        assertEquals("new.m4a", projected.localFileName)
        assertNull(projectDownloadedSongMetadata(existing, update.copy(localFileName = null))
            .resolvedLocalFileName())
    }

    @Test
    fun `metadata-only edit retains known name and private reference move uses new basename`() {
        val existing = song().copy(localFileName = "old.m4a")
        assertEquals("old.m4a", projectDownloadedSongMetadata(existing, localSong())
            .localFileName)
        assertEquals("new name.m4a", projectDownloadedSongMetadata(existing,
            localSong().copy(mediaUri = "file:///music/new%20name.m4a")).localFileName)
    }

    @Test
    fun `active content reference does not inherit an old private path name`() {
        assertNull(song().copy(filePath = "/music/old.m4a").resolvedLocalFileName())
    }

    private fun song() = DownloadedSong(
        id = 42L, name = "title", artist = "artist", album = "netease",
        filePath = OLD_REFERENCE, fileSize = 4L, downloadTime = 1L,
        mediaUri = OLD_REFERENCE, stableKey = "42|netease|",
        sourceIdentityAlbum = "netease", sourceChannelId = "netease", sourceAudioId = "42"
    )

    private fun localSong() = SongItem(
        id = 42L, name = "edited title", artist = "artist", album = "netease",
        albumId = 0L, durationMs = 1L, coverUrl = null, mediaUri = OLD_REFERENCE
    )

    private companion object {
        const val OLD_REFERENCE = "content://provider/document/opaque%2Fnode"
        const val NEW_REFERENCE = "content://provider/document/different%2Fnode"
    }
}
