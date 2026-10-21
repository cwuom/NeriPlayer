package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.store.stats.toDomain

import moe.ouom.neriplayer.data.history.toPlayedEntry
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryRoomMapperTest {
    private val mapper = PlayHistoryRoomMapper()

    @Test
    fun `Room history keeps selected local provenance alongside remote metadata identity`() {
        val reference = "content://media/external/audio/media/42"
        val source = SongItem(
            id = 42L, name = "Scanned title", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 1_000L, coverUrl = null, mediaUri = reference,
            channelId = "netease", audioId = "42", explicitLocalSourceReference = reference
        )

        val restored = mapper.toDomain(mapper.toEntities(listOf(source.toPlayedEntry(100L)))).single().toSongItem()

        assertEquals(reference, restored.explicitLocalSourceReference)
        assertEquals(reference, restored.mediaUri)
        assertEquals("netease", restored.channelId)
        assertEquals("42", restored.audioId)
    }

    @Test
    fun `Room history rejects provenance for another source on both write and read`() {
        val reference = "content://media/external/audio/media/42"
        val source = SongItem(
            id = 42L, name = "Scanned title", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 1_000L, coverUrl = null, mediaUri = reference,
            explicitLocalSourceReference = reference
        ).toPlayedEntry(100L)
        val unrelated = "content://media/external/audio/media/43"

        val written = mapper.toEntities(listOf(source.copy(explicitLocalSourceReference = unrelated))).single()
        assertNull(written.explicitLocalSourceReference)
        val malformedRow = mapper.toEntities(listOf(source)).single().copy(explicitLocalSourceReference = unrelated)
        assertNull(mapper.toDomain(listOf(malformedRow)).single().explicitLocalSourceReference)
    }

    @Test
    fun `round trip keeps identity resume position and metadata`() {
        val entries = listOf(
            PlayedEntry(
                id = 42L,
                name = "local title",
                artist = "local artist",
                album = "__local_files__",
                albumId = 0L,
                durationMs = 180_000L,
                resumePositionMs = 12_345L,
                coverUrl = "file:///cover.jpg",
                mediaUri = "content://media/42",
                matchedLyric = "lyric",
                matchedRomanizedLyric = "romanized",
                originalRomanizedLyric = "base romanized",
                matchedLyricSource = moe.ouom.neriplayer.data.model.music.MusicPlatform.CLOUD_MUSIC,
                matchedSongId = "42",
                userLyricOffsetMs = 150L,
                lyricSyncRevision = 300L,
                lyricSyncEdited = true,
                customName = "custom",
                localFileName = "song.flac",
                localFilePath = "/music/song.flac",
                channelId = "local",
                audioId = "42",
                sourceStableKey = "42|netease|",
                playedAt = 200L
            ),
            PlayedEntry(
                id = 7L,
                name = "remote",
                artist = "artist",
                album = "netease",
                durationMs = 210_000L,
                coverUrl = null,
                playedAt = 100L
            )
        )

        val restored = mapper.toDomain(mapper.toEntities(entries))

        assertEquals(entries, restored)
        assertTrue(mapper.validateRoundTrip(entries))
        assertEquals(
            listOf("42|__local_files__|/music/song.flac", "7|netease|"),
            mapper.toEntities(entries).map { it.identityKey }
        )
    }
}
