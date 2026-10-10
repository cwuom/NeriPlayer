package moe.ouom.neriplayer.data.playlist.usage

import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistPlayRecordingTest {

    @Test
    fun `the first play of a playlist starts its device shard and daily bucket`() {
        val recorded = recordLocalPlaylistPlay(emptyList(), playlistId = 7L, playedAt = FIRST_PLAY, deviceId = "phone")

        val shard = SyncPlaybackCounterShard(
            deviceId = "phone",
            playCount = 1,
            firstPlayedAt = FIRST_PLAY,
            lastPlayedAt = FIRST_PLAY
        )
        assertEquals(
            listOf(
                LocalPlaylistPlaybackStat(
                    playlistId = 7L,
                    totalPlayCount = 1L,
                    firstPlayedAt = FIRST_PLAY,
                    lastPlayedAt = FIRST_PLAY,
                    counterShards = listOf(shard),
                    dailyPlayBuckets = listOf(
                        LocalPlaylistPlayBucket(
                            dayStartAt = DAY_START,
                            playCount = 1L,
                            firstPlayedAt = FIRST_PLAY,
                            lastPlayedAt = FIRST_PLAY,
                            counterShards = listOf(shard)
                        )
                    )
                )
            ),
            recorded
        )
    }

    @Test
    fun `later plays extend the same day bucket or open the next one`() {
        val first = recordLocalPlaylistPlay(emptyList(), 7L, FIRST_PLAY, "phone")
        val sameDay = recordLocalPlaylistPlay(first, 7L, SECOND_PLAY, "phone")
        val nextDay = recordLocalPlaylistPlay(sameDay, 7L, NEXT_DAY_PLAY, "tablet")

        val stat = nextDay.single()
        assertEquals(3L, stat.totalPlayCount)
        assertEquals(FIRST_PLAY, stat.firstPlayedAt)
        assertEquals(NEXT_DAY_PLAY, stat.lastPlayedAt)
        assertEquals(listOf("phone" to 2, "tablet" to 1), stat.counterShards.map { it.deviceId to it.playCount })
        assertEquals(
            listOf(DAY_START to 2L, playbackStatsDayStartAt(NEXT_DAY_PLAY) to 1L),
            stat.dailyPlayBuckets.map { it.dayStartAt to it.playCount }
        )
        assertEquals(FIRST_PLAY to SECOND_PLAY, stat.dailyPlayBuckets.first().let { it.firstPlayedAt to it.lastPlayedAt })
    }

    @Test
    fun `a legacy total becomes the counter base and other playlists keep their counts`() {
        val other = LocalPlaylistPlaybackStat(playlistId = 3L, totalPlayCount = 2L, firstPlayedAt = 10L, lastPlayedAt = 20L)
        val legacy = LocalPlaylistPlaybackStat(playlistId = 9L, totalPlayCount = 5L, firstPlayedAt = 100L, lastPlayedAt = 200L)

        val recorded = recordLocalPlaylistPlay(listOf(legacy, other), 9L, FIRST_PLAY, "phone")

        assertEquals(listOf(3L to 2L, 9L to 6L), recorded.map { it.playlistId to it.totalPlayCount })
        assertTrue(recorded.first().dailyPlayBuckets.isEmpty())
        val updated = recorded.last()
        assertEquals(5L, updated.counterBasePlayCount)
        assertEquals(100L to FIRST_PLAY, updated.firstPlayedAt to updated.lastPlayedAt)
        assertEquals(listOf("phone" to 1), updated.counterShards.map { it.deviceId to it.playCount })
        assertEquals(listOf(DAY_START to 1L), updated.dailyPlayBuckets.map { it.dayStartAt to it.playCount })
    }

    @Test
    fun `plays without a playlist id record nothing`() {
        val current = listOf(
            LocalPlaylistPlaybackStat(playlistId = 7L, totalPlayCount = 3L, firstPlayedAt = 100L, lastPlayedAt = 200L)
        )

        val recorded = recordLocalPlaylistPlay(current, playlistId = 0L, playedAt = FIRST_PLAY)

        val stat = recorded.single()
        assertEquals(7L to 3L, stat.playlistId to stat.totalPlayCount)
        assertEquals(200L, stat.lastPlayedAt)
        assertTrue(stat.counterShards.isEmpty())
        assertTrue(stat.dailyPlayBuckets.isEmpty())
    }

    private companion object {
        const val HOUR = 3_600_000L
        val DAY_START = playbackStatsDayStartAt(1_759_989_600_000L)
        val FIRST_PLAY = DAY_START + HOUR
        val SECOND_PLAY = DAY_START + 2 * HOUR
        val NEXT_DAY_PLAY = DAY_START + 26 * HOUR
    }
}
