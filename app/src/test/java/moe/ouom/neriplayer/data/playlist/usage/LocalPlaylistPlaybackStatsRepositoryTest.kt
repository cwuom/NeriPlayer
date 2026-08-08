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

    @Test
    fun `room recovery keeps Room-only stats and merges local playback delta once`() {
        val baseline = recordLocalPlaylistPlay(
            current = emptyList(),
            playlistId = 1L,
            playedAt = 100L,
            deviceId = "local"
        )
        val current = recordLocalPlaylistPlay(
            current = baseline,
            playlistId = 1L,
            playedAt = 200L,
            deviceId = "local"
        )
        val roomSnapshot = baseline + recordLocalPlaylistPlay(
            current = emptyList(),
            playlistId = 2L,
            playedAt = 150L,
            deviceId = "remote"
        )

        val recovered = mergeLocalPlaylistPlaybackRoomRecovery(
            roomSnapshot = roomSnapshot,
            recoveryBaseline = baseline,
            currentSnapshot = current
        )

        assertEquals(2, recovered.size)
        assertEquals(2L, recovered.first { it.playlistId == 1L }.totalPlayCount)
        assertEquals(1L, recovered.first { it.playlistId == 2L }.totalPlayCount)
    }
}
