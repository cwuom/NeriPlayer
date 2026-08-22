package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.json.JSONObject

internal enum class LegacyDownloadUpgradeRowStatus {
    COMPLETED,
    AUDIO_NOT_FOUND,
    STORAGE_UNAVAILABLE,
    PROVIDER_FAILURE,
    INVALID_PAYLOAD
}

internal data class LegacyDownloadUpgradeRowResult(
    val stableKey: String,
    val status: LegacyDownloadUpgradeRowStatus,
    val detail: String? = null
)

internal data class LegacyDownloadUpgradeResult(
    val tableFound: Boolean,
    val rowsSeen: Int,
    val rowsCompleted: Int,
    val rowsPending: Int,
    val rowResults: List<LegacyDownloadUpgradeRowResult>,
    val temporaryTableCleaned: Boolean
) {
    val isComplete: Boolean
        get() = !tableFound || (rowsPending == 0 && temporaryTableCleaned)
}

/**
 * 把 v15 迁移留下的一次性 payload 逐行落到托管 root
 */
internal class LegacyDownloadUpgradeCoordinator(
    private val context: Context,
    private val database: NeriUserDataDatabase =
        NeriUserDataDatabase.getInstance(context.applicationContext)
) {
    suspend fun execute(): LegacyDownloadUpgradeResult = withContext(Dispatchers.IO) {
        val sqliteDatabase = database.openHelper.writableDatabase
        if (!tableExists(sqliteDatabase)) {
            return@withContext LegacyDownloadUpgradeResult(
                tableFound = false,
                rowsSeen = 0,
                rowsCompleted = 0,
                rowsPending = 0,
                rowResults = emptyList(),
                temporaryTableCleaned = false
            )
        }

        val rows = readRows(sqliteDatabase)
        if (rows.isEmpty()) {
            return@withContext LegacyDownloadUpgradeResult(
                tableFound = true,
                rowsSeen = 0,
                rowsCompleted = 0,
                rowsPending = 0,
                rowResults = emptyList(),
                temporaryTableCleaned = cleanTemporaryTable(sqliteDatabase)
            )
        }

        val snapshot = runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }.getOrElse { error ->
            val failedRows = rows.map { row ->
                LegacyDownloadUpgradeRowResult(
                    stableKey = row.stableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = error.message
                )
            }
            return@withContext LegacyDownloadUpgradeResult(
                tableFound = true,
                rowsSeen = rows.size,
                rowsCompleted = 0,
                rowsPending = rows.size,
                rowResults = failedRows,
                temporaryTableCleaned = false
            )
        }
        if (!snapshot.rootEntriesComplete) {
            val blockedRows = rows.map { row ->
                LegacyDownloadUpgradeRowResult(
                    stableKey = row.stableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = "managed root enumeration was incomplete"
                )
            }
            return@withContext LegacyDownloadUpgradeResult(
                tableFound = true,
                rowsSeen = rows.size,
                rowsCompleted = 0,
                rowsPending = rows.size,
                rowResults = blockedRows,
                temporaryTableCleaned = false
            )
        }

        val rowResults = rows.map { row -> processRow(sqliteDatabase, row, snapshot) }
        val pending = rowResults.count { it.status != LegacyDownloadUpgradeRowStatus.COMPLETED }
        val cleaned = if (pending == 0) cleanTemporaryTable(sqliteDatabase) else false
        LegacyDownloadUpgradeResult(
            tableFound = true,
            rowsSeen = rowResults.size,
            rowsCompleted = rowResults.count {
                it.status == LegacyDownloadUpgradeRowStatus.COMPLETED
            },
            rowsPending = pending,
            rowResults = rowResults,
            temporaryTableCleaned = cleaned
        )
    }

    private suspend fun processRow(
        sqliteDatabase: androidx.sqlite.db.SupportSQLiteDatabase,
        row: PayloadRow,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): LegacyDownloadUpgradeRowResult {
        val payload = runCatching { JSONObject(row.payloadJson) }.getOrElse { error ->
            return LegacyDownloadUpgradeRowResult(
                stableKey = row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
                detail = error.message
            )
        }
        val effectiveStableKey = payload.optString("stableKey")
            .takeIf(String::isNotBlank)
            ?: row.stableKey
        if (effectiveStableKey.isBlank()) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
                detail = "stableKey is blank"
            )
        }

        val storageResolvable = runCatching {
            ManagedDownloadStorage.isStorageRootResolvable(context)
        }.getOrElse { error ->
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                detail = error.message
            )
        }
        if (!storageResolvable) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.STORAGE_UNAVAILABLE,
                detail = "managed root is unavailable"
            )
        }

        val audio = try {
            resolveAudio(payload, snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                detail = error.message
            )
        }
        if (audio == null) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.AUDIO_NOT_FOUND,
                detail = "managed audio is not visible"
            )
        }

        return try {
            val existingEntry = ManagedDownloadStorage.findMetadataForAudio(context, audio)
            val existingJson = existingEntry
                ?.let { ManagedDownloadStorage.readText(context, it.reference) }
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            val merged = LegacyDownloadUpgradeMetadataMerger.merge(
                payload = payload,
                existing = existingJson,
                audioFileName = audio.logicalName
            ).apply {
                put("stableKey", effectiveStableKey)
                put("audioFileName", audio.logicalName)
                put("mediaUri", audio.mediaUri)
                if (audio.localFilePath != null) {
                    put("localFilePath", audio.localFilePath)
                }
            }
            val metadataJson = merged.toString()
            if (!ManagedDownloadStorage.saveMetadata(context, audio, metadataJson)) {
                return LegacyDownloadUpgradeRowResult(
                    stableKey = effectiveStableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = "metadata write was not verified"
                )
            }
            if (!verifyMetadata(context, audio, metadataJson)) {
                return LegacyDownloadUpgradeRowResult(
                    stableKey = effectiveStableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = "metadata read-back verification failed"
                )
            }
            deletePayloadRow(sqliteDatabase, row.stableKey)
            LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.COMPLETED
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                detail = error.message
            )
        }
    }

    private fun resolveAudio(
        payload: JSONObject,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): ManagedDownloadStorage.StoredEntry? {
        val references = listOfNotNull(
            payload.optString("mediaUri").takeIf(String::isNotBlank),
            payload.optString("filePath").takeIf(String::isNotBlank),
            payload.optString("sourceMediaUri").takeIf(String::isNotBlank)
        ).distinct()
        references.forEach { reference ->
            snapshot.audioEntries.firstOrNull { entry ->
                entry.reference == reference ||
                    entry.mediaUri == reference ||
                    entry.localFilePath == reference
            }?.let { return it }
        }

        val names = buildList {
            payload.optString("audioFileName").takeIf(String::isNotBlank)?.let(::add)
            payload.optString("name").takeIf(String::isNotBlank)?.let { name ->
                payload.optString("filePath")
                    .takeIf(String::isNotBlank)
                    ?.let { path -> File(path).name.takeIf(String::isNotBlank) }
                    ?.let(::add)
                add(name)
            }
            payload.optString("filePath")
                .takeIf(String::isNotBlank)
                ?.let { path -> File(path).name.takeIf(String::isNotBlank) }
                ?.let(::add)
        }.distinct()
        names.forEach { name ->
            snapshot.audioEntries.firstOrNull { entry -> entry.name == name }?.let { return it }
        }
        return null
    }

    private suspend fun verifyMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        expectedJson: String
    ): Boolean {
        val entry = ManagedDownloadStorage.findMetadataForAudio(context, audio) ?: return false
        val raw = ManagedDownloadStorage.readText(context, entry.reference) ?: return false
        val expected = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(expectedJson)
            ?: return false
        val actual = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(raw)
        return actual == expected
    }

    private data class PayloadRow(
        val stableKey: String,
        val payloadJson: String
    )

    private fun readRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase
    ): List<PayloadRow> {
        return database.query(
            "SELECT stable_key, payload_json FROM $PAYLOAD_TABLE " +
                "ORDER BY stable_key ASC"
        ).use { cursor ->
            val stableKeyIndex = cursor.getColumnIndexOrThrow("stable_key")
            val payloadIndex = cursor.getColumnIndexOrThrow("payload_json")
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PayloadRow(
                            stableKey = cursor.getString(stableKeyIndex),
                            payloadJson = cursor.getString(payloadIndex)
                        )
                    )
                }
            }
        }
    }

    private fun deletePayloadRow(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        stableKey: String
    ) {
        database.execSQL(
            "DELETE FROM $PAYLOAD_TABLE WHERE stable_key = ?",
            arrayOf(stableKey)
        )
    }

    private fun tableExists(database: androidx.sqlite.db.SupportSQLiteDatabase): Boolean {
        return database.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(PAYLOAD_TABLE)
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun cleanTemporaryTable(database: androidx.sqlite.db.SupportSQLiteDatabase): Boolean {
        return runCatching {
            database.execSQL("DROP TABLE IF EXISTS $PAYLOAD_TABLE")
            true
        }.getOrDefault(false)
    }

    companion object {
        internal const val PAYLOAD_TABLE = "legacy_download_upgrade_payload"
    }
}
