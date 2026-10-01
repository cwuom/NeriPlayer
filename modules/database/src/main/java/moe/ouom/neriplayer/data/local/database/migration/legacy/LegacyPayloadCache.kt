package moe.ouom.neriplayer.data.local.database.migration.legacy

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

internal class LegacyPayloadCache(
    private val db: SupportSQLiteDatabase
) {
    private val entries = object : LinkedHashMap<String, JSONObject>(
        LEGACY_PAYLOAD_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, JSONObject>?
        ): Boolean = size > LEGACY_PAYLOAD_CACHE_MAX_ENTRIES
    }

    fun getOrLoad(
        stableKey: String,
        pendingWrites: Map<String, String>
    ): JSONObject {
        entries[stableKey]?.let { return it }
        val rawPayload = pendingWrites[stableKey] ?: db.query(
            "SELECT `payload_json` FROM `legacy_download_upgrade_payload` " +
                "WHERE `stable_key` = ? LIMIT 1",
            arrayOf(stableKey)
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        }
        val payload = rawPayload
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: JSONObject()
        entries[stableKey] = payload
        return payload
    }

    fun put(stableKey: String, payload: JSONObject) {
        entries[stableKey] = payload
    }
}

internal const val LEGACY_PAYLOAD_CACHE_MAX_ENTRIES = 96
