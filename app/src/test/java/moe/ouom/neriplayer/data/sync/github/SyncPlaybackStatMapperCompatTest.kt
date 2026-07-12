package moe.ouom.neriplayer.data.sync.github

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaybackStatMapperCompatTest {
    private val gson = Gson()

    @Test
    fun `gson legacy track stat missing counter shards normalizes as empty`() {
        val stat = gson.fromJson(
            """
            {
              "identityKey": "netease:1",
              "name": "song",
              "artist": "artist",
              "album": "album",
              "totalListenMs": 1000,
              "playCount": 1,
              "lastPlayedAt": 2000,
              "firstPlayedAt": 1000
            }
            """.trimIndent(),
            SyncTrackStat::class.java
        )

        assertEquals(
            emptyList<SyncPlaybackCounterShard>(),
            SyncPlaybackStatMapper.normalizeCounterShards(stat.counterShards)
        )
    }

    @Test
    fun `gson legacy playback bucket null counter shards normalizes as empty`() {
        val bucket = gson.fromJson(
            """
            {
              "dayStartAt": 0,
              "identityKey": "netease:1",
              "name": "song",
              "artist": "artist",
              "album": "album",
              "totalListenMs": 1000,
              "playCount": 1,
              "lastPlayedAt": 2000,
              "firstPlayedAt": 1000,
              "counterShards": null
            }
            """.trimIndent(),
            SyncPlaybackStatBucket::class.java
        )

        assertEquals(
            emptyList<SyncPlaybackCounterShard>(),
            SyncPlaybackStatMapper.normalizeCounterShards(bucket.counterShards)
        )
    }
}
