package moe.ouom.neriplayer.data.local.database.migration.legacy

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun copyV15DownloadPayload(db: SupportSQLiteDatabase) {
    val catalogLookup = buildLegacyCatalogLookup(db)
    val payloadCache = LegacyPayloadCache(db)
    copyLegacyTableRows(
        db = db,
        tableName = "download_pending_queue",
        catalogLookup = catalogLookup,
        payloadCache = payloadCache
    )
    copyLegacyTableRows(
        db = db,
        tableName = "download_cancelled_key",
        catalogLookup = catalogLookup,
        payloadCache = payloadCache
    )
    copyLegacyTableRows(
        db = db,
        tableName = "downloaded_song_catalog",
        catalogLookup = catalogLookup,
        payloadCache = payloadCache
    )
    copyLegacyTableRows(
        db = db,
        tableName = "download_snapshot_entry",
        catalogLookup = catalogLookup,
        payloadCache = payloadCache
    )
    copyLegacyTableRows(
        db = db,
        tableName = "download_snapshot_metadata",
        catalogLookup = catalogLookup,
        payloadCache = payloadCache
    )
    copyLegacyTableRows(
        db = db,
        tableName = "managed_download_artifact",
        catalogLookup = catalogLookup,
        payloadCache = payloadCache
    )
}

internal fun dropLegacyDownloadProjectionTables(db: SupportSQLiteDatabase) {
    LEGACY_DOWNLOAD_PROJECTION_TABLES.forEach { tableName ->
        db.execSQL("DROP TABLE IF EXISTS `$tableName`")
    }
}

internal fun copyLegacyTableRows(
    db: SupportSQLiteDatabase,
    tableName: String,
    catalogLookup: LegacyCatalogLookup,
    payloadCache: LegacyPayloadCache
) {
    if (!hasTable(db, tableName)) return
    val pendingWrites = LinkedHashMap<String, String>()
    forEachLegacyBatch(db = db, tableName = tableName) { cursor, columnNames ->
        val row = rowToJson(cursor, columnNames)
        val stableKey = resolveLegacyStableKey(
            tableName = tableName,
            cursor = cursor,
            catalogLookup = catalogLookup
        ) ?: fallbackLegacyStableKey(tableName, cursor)
        val existing = payloadCache.getOrLoad(stableKey, pendingWrites)
        val hadStableKey = hasLegacyStableKey(existing, stableKey)
        val changed = mergeLegacyRow(
            payload = existing,
            tableName = tableName,
            stableKey = stableKey,
            row = row
        )
        if (changed || !hadStableKey) {
            existing.put("stableKey", stableKey)
            val payloadJson = existing.toString()
            pendingWrites[stableKey] = payloadJson
            payloadCache.put(stableKey, existing)
            if (pendingWrites.size >= LEGACY_PAYLOAD_UPSERT_BATCH_SIZE) {
                flushPayloadWrites(db, pendingWrites)
            }
        }
    }
    flushPayloadWrites(db, pendingWrites)
}

private fun hasLegacyStableKey(payload: org.json.JSONObject, stableKey: String): Boolean {
    return payload.has("stableKey") && !payload.isNull("stableKey") &&
        payload.optString("stableKey") == stableKey
}

internal val LEGACY_DOWNLOAD_PROJECTION_TABLES = listOf(
    "download_pending_queue",
    "download_cancelled_key",
    "downloaded_song_catalog",
    "download_snapshot_entry",
    "download_snapshot_metadata",
    "managed_download_artifact"
)
