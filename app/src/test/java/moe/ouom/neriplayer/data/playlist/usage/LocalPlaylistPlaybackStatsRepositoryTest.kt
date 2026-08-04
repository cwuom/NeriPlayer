package moe.ouom.neriplayer.data.playlist.usage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistPlaybackStatsRepositoryTest {

    @Test
    fun `legacy local playlist stats without collection fields normalize safely`() {
        val parsed = Gson().fromJson<List<LocalPlaylistPlaybackStat>>(
            """
            [{
              "playlistId": 42,
              "totalPlayCount": 7,
              "firstPlayedAt": 100,
              "lastPlayedAt": 200
            }]
            """.trimIndent(),
            object : TypeToken<List<LocalPlaylistPlaybackStat>>() {}.type
        )

        val normalized = normalizeLocalPlaylistPlaybackStats(parsed)

        assertEquals(1, normalized.size)
        assertEquals(7L, normalized.single().totalPlayCount)
        assertTrue(normalized.single().counterShards.isEmpty())
        assertTrue(normalized.single().dailyPlayBuckets.isEmpty())
    }
}
