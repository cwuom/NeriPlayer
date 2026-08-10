package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.history.PlayedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryRoomMapperTest {
    private val mapper = PlayHistoryRoomMapper()

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
