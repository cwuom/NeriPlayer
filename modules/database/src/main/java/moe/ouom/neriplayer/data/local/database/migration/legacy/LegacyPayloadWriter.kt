package moe.ouom.neriplayer.data.local.database.migration.legacy

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun flushPayloadWrites(
    db: SupportSQLiteDatabase,
    pendingWrites: LinkedHashMap<String, String>
) {
    while (pendingWrites.isNotEmpty()) {
        val batch = pendingWrites.entries
            .take(LEGACY_PAYLOAD_UPSERT_BATCH_SIZE)
        val placeholders = List(batch.size) { "(?, ?)" }.joinToString(",")
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO `legacy_download_upgrade_payload` " +
                "(`stable_key`, `payload_json`) VALUES $placeholders"
        )
        statement.use { insertStatement ->
            batch.forEachIndexed { index, entry ->
                val parameterOffset = index * 2
                insertStatement.bindString(parameterOffset + 1, entry.key)
                insertStatement.bindString(parameterOffset + 2, entry.value)
            }
            insertStatement.executeInsert()
        }
        batch.forEach { entry -> pendingWrites.remove(entry.key) }
    }
}

internal const val LEGACY_PAYLOAD_UPSERT_BATCH_SIZE = 48
