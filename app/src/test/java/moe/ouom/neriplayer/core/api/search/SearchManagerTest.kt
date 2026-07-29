package moe.ouom.neriplayer.core.api.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchManagerTest {

    @Test
    fun `selectBestSearchCandidate accepts only a nearby same-song candidate`() {
        val result = SearchManager.selectBestSearchCandidate(
            songName = "Signal",
            songArtist = "Artist One / Artist Two",
            songDurationMs = 180_000L,
            candidates = listOf(
                candidate(id = "wrong-artist", singer = "Another Artist", duration = "3:00"),
                candidate(id = "wrong-duration", duration = "3:30"),
                candidate(
                    id = "match",
                    singer = "Artist Two/Artist One",
                    duration = "3:08"
                )
            )
        )

        assertEquals("match", result?.id)
    }

    @Test
    fun `selectBestSearchCandidate rejects unknown or distant duration`() {
        val nearbyCandidate = candidate(id = "nearby", duration = "3:08")

        assertNull(
            SearchManager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 0L,
                candidates = listOf(nearbyCandidate)
            )
        )
        assertNull(
            SearchManager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 180_000L,
                candidates = listOf(candidate(id = "distant", duration = "4:00"))
            )
        )
    }

    private fun candidate(
        id: String,
        singer: String = "Artist One",
        duration: String
    ): SongSearchInfo {
        return SongSearchInfo(
            id = id,
            songName = "Signal",
            singer = singer,
            duration = duration,
            source = MusicPlatform.CLOUD_MUSIC,
            albumName = null,
            coverUrl = null
        )
    }
}
