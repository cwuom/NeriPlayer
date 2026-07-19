package moe.ouom.neriplayer.data.sync.github

import com.google.gson.Gson
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaybackStatMapperTest {
    private val gson = Gson()

    @Test
    fun `sanitize keeps legacy stat when counter shards field is missing`() {
        val stat = gson.fromJson(
            """
            {
              "identityKey": "netease:42",
              "name": "song",
              "artist": "artist",
              "album": "album",
              "totalListenMs": 120000,
              "playCount": 2,
              "lastPlayedAt": 2000,
              "firstPlayedAt": 1000
            }
            """.trimIndent(),
            SyncTrackStat::class.java
        )

        val normalized = SyncPlaybackStatsMergePolicy.merge(
            local = emptyList(),
            remote = listOf(stat),
            playbackStatsClearedAt = 0L
        )

        assertEquals(1, normalized.size)
        assertTrue(normalized.single().counterShards.isEmpty())
        assertEquals(120000L, normalized.single().totalListenMs)
        assertEquals(2, normalized.single().playCount)
    }

    @Test
    fun `sanitize keeps legacy bucket when counter shards field is missing`() {
        val bucket = gson.fromJson(
            """
            {
              "dayStartAt": 86400000,
              "identityKey": "netease:42",
              "name": "song",
              "artist": "artist",
              "album": "album",
              "totalListenMs": 60000,
              "playCount": 1,
              "lastPlayedAt": 2000,
              "firstPlayedAt": 1000
            }
            """.trimIndent(),
            SyncPlaybackStatBucket::class.java
        )

        val normalized = SyncPlaybackStatsMergePolicy.mergeBuckets(
            local = emptyList(),
            remote = listOf(bucket),
            playbackStatsClearedAt = 0L
        )

        assertEquals(1, normalized.size)
        assertTrue(normalized.single().counterShards.isEmpty())
        assertEquals(60000L, normalized.single().totalListenMs)
        assertEquals(1, normalized.single().playCount)
    }
}
