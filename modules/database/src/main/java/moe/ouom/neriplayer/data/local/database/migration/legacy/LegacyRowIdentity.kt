package moe.ouom.neriplayer.data.local.database.migration.legacy

import android.database.Cursor

internal fun resolveLegacyStableKey(
    tableName: String,
    cursor: Cursor,
    catalogLookup: LegacyCatalogLookup
): String? {
    val directStableKey = cursorString(cursor, "stable_key")
    if (directStableKey != null) return directStableKey
    if (tableName == "downloaded_song_catalog") return deriveCatalogStableKey(cursor)
    return legacySnapshotStableKey(tableName, cursor, catalogLookup)
}

private fun legacySnapshotStableKey(
    tableName: String,
    cursor: Cursor,
    catalogLookup: LegacyCatalogLookup
): String? {
    return when {
        tableName == "download_snapshot_metadata" -> findCatalogStableKeyForAudioName(
            catalogLookup = catalogLookup,
            audioName = cursorString(cursor, "audio_name")
        )
        tableName == "download_snapshot_entry" -> findCatalogStableKeyForSnapshotEntry(
            catalogLookup = catalogLookup,
            reference = cursorString(cursor, "reference"),
            mediaUri = cursorString(cursor, "media_uri"),
            name = cursorString(cursor, "name")
        ) ?: deriveSnapshotEntryStableKey(cursor)
        else -> null
    }
}

internal fun deriveCatalogStableKey(cursor: Cursor): String? {
    val id = cursorLong(cursor, "id")?.takeIf { it != 0L } ?: return null
    val filePath = cursorString(cursor, "file_path")
    val mediaUri = cursorString(cursor, "media_uri")
    val localReference = listOfNotNull(filePath, mediaUri)
        .firstOrNull(::isLegacyLocalReference)
        ?.let(::normalizeLegacyLocalReference)
        ?: return null
    return "$id|__local_files__|$localReference"
}

internal fun deriveSnapshotEntryStableKey(cursor: Cursor): String? {
    val rootKey = cursorString(cursor, "root_key") ?: return null
    val entryKey = cursorString(cursor, "entry_key")
        ?: cursorString(cursor, "reference")
        ?: cursorString(cursor, "name")
        ?: return null
    return "legacy-snapshot:$rootKey:$entryKey"
}

internal fun fallbackLegacyStableKey(
    tableName: String,
    cursor: Cursor
): String {
    val identityColumns = when (tableName) {
        "download_pending_queue", "download_cancelled_key" ->
            listOf("stable_key", "queued_at_ms", "cancelled_at_ms")
        "downloaded_song_catalog" ->
            listOf("catalog_key", "root_key", "display_position", "id")
        "download_snapshot_entry" ->
            listOf("root_key", "bucket", "entry_key", "display_position")
        "download_snapshot_metadata" ->
            listOf("root_key", "audio_name")
        "managed_download_artifact" ->
            listOf("root_key", "stable_key", "artifact_id")
        else -> cursor.columnNames.toList()
    }
    val identity = identityColumns.mapNotNull { columnName ->
        cursorString(cursor, columnName)?.let { value ->
            "$columnName=$value"
        }
    }.joinToString("|").ifBlank { "row-${cursor.position}" }
    return "legacy:$tableName:$identity"
}

internal fun isLegacyLocalReference(reference: String): Boolean {
    return reference.startsWith("/") ||
        reference.startsWith("file:", ignoreCase = true) ||
        reference.startsWith("content:", ignoreCase = true)
}

internal fun normalizeLegacyLocalReference(reference: String): String {
    if (!reference.startsWith("file://", ignoreCase = true)) return reference
    return runCatching { java.net.URI(reference).path }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: reference
}

internal fun cursorString(cursor: Cursor, columnName: String): String? {
    val index = cursor.getColumnIndex(columnName)
    if (index < 0 || cursor.isNull(index)) return null
    return cursor.getString(index)?.trim()?.takeIf(String::isNotBlank)
}

internal fun cursorLong(cursor: Cursor, columnName: String): Long? {
    val index = cursor.getColumnIndex(columnName)
    if (index < 0 || cursor.isNull(index)) return null
    return cursor.getLong(index)
}
