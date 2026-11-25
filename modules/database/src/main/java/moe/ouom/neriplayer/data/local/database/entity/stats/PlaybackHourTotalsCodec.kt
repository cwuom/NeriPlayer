package moe.ouom.neriplayer.data.local.database.entity.stats

import androidx.room.TypeConverter
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal
import org.json.JSONArray
import org.json.JSONObject

object PlaybackHourTotalsCodec {
    // 每个日桶有两列小时数据，给曲目信息和行开销预留 CursorWindow 空间
    internal const val MAX_UTF8_BYTES = 512 * 1024

    @JvmStatic
    @TypeConverter
    fun encode(totals: List<SyncPlaybackHourTotal>): String {
        if (totals.isEmpty()) return "[]"
        val rows = StringBuilder("[")
        for (total in totals) {
            if (rows.length > 1) rows.append(',')
            val remaining = MAX_UTF8_BYTES - rows.length - 1
            requireFits(total.deviceId.length, remaining)
            val row = JSONObject()
                .put("deviceId", total.deviceId)
                .put("epochStartedAt", total.epochStartedAt)
                .put("hourStartAt", total.hourStartAt)
                .put("localHour", total.localHour)
                .put("totalListenMs", total.totalListenMs)
                .toString()
            requireFits(row.length, remaining)
            rows.append(row)
        }
        val json = rows.append(']').toString()
        // UTF-8 每个 UTF-16 代码单元最多占三字节，短记录无需额外编码
        if (json.length > MAX_UTF8_BYTES / 3) requireFits(json.toByteArray(Charsets.UTF_8).size, MAX_UTF8_BYTES)
        return json
    }

    private fun requireFits(size: Int, remaining: Int) {
        require(size <= remaining) { "Playback hour totals exceed the $MAX_UTF8_BYTES byte column limit" }
    }

    @JvmStatic
    @TypeConverter
    fun decode(encoded: String): List<SyncPlaybackHourTotal> {
        if (encoded == "[]") return emptyList()
        val rows = JSONArray(encoded)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            SyncPlaybackHourTotal(
                deviceId = row.getString("deviceId"),
                epochStartedAt = row.getLong("epochStartedAt"),
                hourStartAt = row.getLong("hourStartAt"),
                localHour = row.getInt("localHour"),
                totalListenMs = row.getLong("totalListenMs")
            )
        }
    }
}
