package moe.ouom.neriplayer.data.sync.mapping.stats

import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHours
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaybackStatMappingCompatTest {
    private val gson = Gson()

    @Test
    fun `absent and null legacy hourly data cannot displace a known device total`() {
        val known = listOf(SyncPlaybackHourTotal("phone", 0, 0, 0, 30))
        val rows = listOf("{}", """{"hourlyTotals":null}""",
            """{"hourlyTotals":[null,{"deviceId":null,"totalListenMs":90}]}""")
        for (json in rows) {
            val old = gson.fromJson(json, SyncPlaybackStatBucket::class.java)
            assertEquals(known, SyncPlaybackHours.merge(old.hourlyTotals, known))
        }
    }

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
            SyncPlaybackStatMapping.normalizeCounterShards(stat.counterShards)
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
            SyncPlaybackStatMapping.normalizeCounterShards(bucket.counterShards)
        )
    }
}
