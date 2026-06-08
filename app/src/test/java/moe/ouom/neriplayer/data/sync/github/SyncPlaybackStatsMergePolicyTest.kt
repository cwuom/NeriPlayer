package moe.ouom.neriplayer.data.sync.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaybackStatsMergePolicyTest {
    @Test
    fun `clear barrier drops remote stats recorded before clear`() {
        val clearedAt = 1_000L
        val remote = trackStat(
            identityKey = "remote-old",
            firstPlayedAt = 100L,
            lastPlayedAt = 900L
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = emptyList(),
            remote = listOf(remote),
            playbackStatsClearedAt = clearedAt
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `clear barrier keeps stats created after clear`() {
        val clearedAt = 1_000L
        val remote = trackStat(
            identityKey = "remote-new",
            firstPlayedAt = 1_100L,
            lastPlayedAt = 1_200L
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = emptyList(),
            remote = listOf(remote),
            playbackStatsClearedAt = clearedAt
        )

        assertEquals(listOf(remote), merged)
    }

    @Test
    fun `clear barrier keeps stats updated after clear even when first play is old`() {
        val clearedAt = 1_000L
        val local = trackStat(
            identityKey = "local-resumed",
            firstPlayedAt = 100L,
            lastPlayedAt = 1_200L
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = listOf(local),
            remote = emptyList(),
            playbackStatsClearedAt = clearedAt
        )

        assertEquals(1, merged.size)
        assertEquals("local-resumed", merged.single().identityKey)
        assertEquals(1_200L, merged.single().firstPlayedAt)
        assertEquals(1_200L, merged.single().lastPlayedAt)
    }

    @Test
    fun `merge without clear barrier keeps larger counters`() {
        val local = trackStat(
            identityKey = "same",
            totalListenMs = 1_000L,
            playCount = 1,
            firstPlayedAt = 100L,
            lastPlayedAt = 200L
        )
        val remote = trackStat(
            identityKey = "same",
            totalListenMs = 2_000L,
            playCount = 3,
            firstPlayedAt = 50L,
            lastPlayedAt = 300L,
            name = "newer"
        )

        val merged = SyncPlaybackStatsMergePolicy.merge(
            local = listOf(local),
            remote = listOf(remote),
            playbackStatsClearedAt = 0L
        )

        assertEquals(1, merged.size)
        assertEquals("newer", merged.single().name)
        assertEquals(2_000L, merged.single().totalListenMs)
        assertEquals(3, merged.single().playCount)
        assertEquals(50L, merged.single().firstPlayedAt)
        assertEquals(300L, merged.single().lastPlayedAt)
    }

    private fun trackStat(
        identityKey: String,
        totalListenMs: Long = 1_000L,
        playCount: Int = 1,
        firstPlayedAt: Long,
        lastPlayedAt: Long,
        name: String = identityKey
    ): SyncTrackStat {
        return SyncTrackStat(
            identityKey = identityKey,
            name = name,
            artist = "artist",
            album = "album",
            totalListenMs = totalListenMs,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt
        )
    }
}
