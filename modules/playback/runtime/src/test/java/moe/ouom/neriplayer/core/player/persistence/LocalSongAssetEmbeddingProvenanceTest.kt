package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSongAssetEmbeddingProvenanceTest {

    private val matched = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 2L,
        durationMs = 180_000L,
        coverUrl = null,
        matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "old"
    )

    @Test
    fun `re-embedding existing lyrics keeps the current provenance`() {
        val updated = matched.withLyricProvenance(LocalSongAssetEmbedding(lyrics = "[00:01.00]a"))

        assertEquals(MusicPlatform.CLOUD_MUSIC, updated.matchedLyricSource)
        assertEquals("old", updated.matchedSongId)
    }

    @Test
    fun `a recordable online source replaces the provenance`() {
        val updated = matched.withLyricProvenance(
            LocalSongAssetEmbedding(
                lyrics = "[00:01.00]a",
                replacesLyricProvenance = true,
                matchedLyricSource = MusicPlatform.QQ_MUSIC,
                matchedSongId = "mid"
            )
        )

        assertEquals(MusicPlatform.QQ_MUSIC, updated.matchedLyricSource)
        assertEquals("mid", updated.matchedSongId)
    }

    @Test
    fun `an unrecordable online source clears the stale provenance`() {
        val unrecordable = matched.withLyricProvenance(
            LocalSongAssetEmbedding(lyrics = "[00:01.00]a", replacesLyricProvenance = true)
        )
        val blankId = matched.withLyricProvenance(
            LocalSongAssetEmbedding(
                lyrics = "[00:01.00]a",
                replacesLyricProvenance = true,
                matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
                matchedSongId = " "
            )
        )

        assertNull(unrecordable.matchedLyricSource)
        assertNull(unrecordable.matchedSongId)
        assertNull(blankId.matchedLyricSource)
        assertNull(blankId.matchedSongId)
    }
}
