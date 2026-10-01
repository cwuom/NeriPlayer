package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacySyncSongMetadataTest {
    @Test
    fun `missing legacy fields use first usable candidate and preserve intentional current values`() {
        val known = SyncSong(id = 7, name = "song", artist = "artist", album = "album", albumId = 8,
            durationMs = 9, coverUrl = "cover", matchedLyric = "lyric", userLyricOffsetMs = -10,
            channelId = "channel", audioId = "audio")
        val resolved = SyncSongMetadataMergePolicy.resolveSelectedPayload(SyncSong(), listOf(SyncSong(), known))
        assertEquals(known, resolved)
        assertEquals(SyncSong(), SyncSongMetadataMergePolicy.resolveSelectedPayload(SyncSong(), emptyList()))
        val selected = known.copy(name = "selected", durationMs = 12, userLyricOffsetMs = 11)
        assertEquals(selected, fillLegacySyncSongMetadata(selected, listOf(known)))
        assertNull(fillLegacySyncSongMetadata(SyncSong(), listOf(SyncSong(coverUrl = " "))).coverUrl)
    }

    @Test
    fun `numeric fallback ignores zero and nonpositive durations`() {
        val zero = SyncSong(durationMs = -5)
        val known = SyncSong(id = 7, albumId = 8, durationMs = 9, userLyricOffsetMs = -10)
        val resolved = fillLegacySyncSongMetadata(SyncSong(durationMs = -1), listOf(zero, known))
        assertEquals(7L, resolved.id)
        assertEquals(8L, resolved.albumId)
        assertEquals(9L, resolved.durationMs)
        assertEquals(-10L, resolved.userLyricOffsetMs)
        assertEquals(0L, fillLegacySyncSongMetadata(SyncSong(), listOf(zero)).durationMs)
    }

    @Test
    fun `payload keys distinguish ambiguous concatenation and nullable metadata`() {
        val first = SyncSong(id = 1, name = "ab", artist = "c")
        val second = first.copy(name = "a", artist = "bc")
        assertNotEquals(syncSongPayloadKey(first), syncSongPayloadKey(second))
        assertEquals(syncSongPayloadKey(first), syncSongPayloadKey(first.copy(coverUrl = "")))
        assertNotEquals(syncSongPayloadKey(first), syncSongPayloadKey(first.copy(customName = "title")))
    }
}
