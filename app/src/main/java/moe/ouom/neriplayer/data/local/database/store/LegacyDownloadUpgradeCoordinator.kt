package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import java.util.UUID
import org.json.JSONObject

internal enum class LegacyDownloadUpgradeRowStatus {
    COMPLETED,
    AUDIO_NOT_FOUND,
    STORAGE_UNAVAILABLE,
    PROVIDER_FAILURE,
    INVALID_PAYLOAD,
    CONFLICT
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
    val temporaryTableCleaned: Boolean,
    val legacyProjectionTablesCleaned: Boolean
) {
    val isComplete: Boolean
        get() = rowsPending == 0 && temporaryTableCleaned && legacyProjectionTablesCleaned
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
                temporaryTableCleaned = true,
                legacyProjectionTablesCleaned = cleanLegacyProjectionTables(sqliteDatabase)
            )
        }

        var rowsSeen = 0
        val rowResults = mutableListOf<LegacyDownloadUpgradeRowResult>()
        var afterStableKey: String? = null
        var managedSnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null
        var snapshotFailure: Throwable? = null
        while (true) {
            val rows = readRowBatch(sqliteDatabase, afterStableKey)
            if (rows.isEmpty()) break
            rowsSeen += rows.size
            rows.forEach { row ->
                val needsSnapshot = legacyPayloadNeedsManagedRootSnapshot(row.payloadJson)
                if (needsSnapshot && managedSnapshot == null && snapshotFailure == null) {
                    runCatching {
                        ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                            context = context,
                            forceRefresh = true
                        )
                    }.onSuccess { snapshot ->
                        managedSnapshot = snapshot
                    }.onFailure { error ->
                        snapshotFailure = error
                    }
                }
                val result = if (needsSnapshot && snapshotFailure != null) {
                    LegacyDownloadUpgradeRowResult(
                        stableKey = row.stableKey,
                        status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                        detail = snapshotFailure.message
                    )
                } else if (needsSnapshot && managedSnapshot?.rootEntriesComplete == false) {
                    LegacyDownloadUpgradeRowResult(
                        stableKey = row.stableKey,
                        status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                        detail = "managed root enumeration was incomplete"
                    )
                } else {
                    processRow(
                        sqliteDatabase = sqliteDatabase,
                        row = row,
                        snapshot = if (needsSnapshot) managedSnapshot else null
                    )
                }
                rowResults += result
            }
            afterStableKey = rows.last().stableKey
        }
        if (rowsSeen == 0) {
            val temporaryTableCleaned = cleanTemporaryTable(sqliteDatabase)
            return@withContext LegacyDownloadUpgradeResult(
                tableFound = true,
                rowsSeen = 0,
                rowsCompleted = 0,
                rowsPending = 0,
                rowResults = emptyList(),
                temporaryTableCleaned = temporaryTableCleaned,
                legacyProjectionTablesCleaned = temporaryTableCleaned &&
                    cleanLegacyProjectionTables(sqliteDatabase)
            )
        }
        return@withContext finishResult(
            database = sqliteDatabase,
            rowsSeen = rowsSeen,
            rowResults = rowResults
        )
    }

    private fun finishResult(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        rowsSeen: Int,
        rowResults: List<LegacyDownloadUpgradeRowResult>
    ): LegacyDownloadUpgradeResult {
        val pending = rowResults.count { it.status != LegacyDownloadUpgradeRowStatus.COMPLETED }
        val temporaryTableCleaned = if (pending == 0) {
            cleanTemporaryTable(database)
        } else {
            false
        }
        val legacyProjectionTablesCleaned = if (pending == 0 && temporaryTableCleaned) {
            cleanLegacyProjectionTables(database)
        } else {
            false
        }
        return LegacyDownloadUpgradeResult(
            tableFound = true,
            rowsSeen = rowsSeen,
            rowsCompleted = rowResults.count {
                it.status == LegacyDownloadUpgradeRowStatus.COMPLETED
            },
            rowsPending = pending,
            rowResults = rowResults,
            temporaryTableCleaned = temporaryTableCleaned,
            legacyProjectionTablesCleaned = legacyProjectionTablesCleaned
        )
    }

    private suspend fun processRow(
        sqliteDatabase: androidx.sqlite.db.SupportSQLiteDatabase,
        row: PayloadRow,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
    ): LegacyDownloadUpgradeRowResult {
        val payload = runCatching { JSONObject(row.payloadJson) }.getOrElse { error ->
            return LegacyDownloadUpgradeRowResult(
                stableKey = row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
                detail = error.message
            )
        }
        val payloadStableKey = payload.optString("stableKey")
            .trim()
            .takeIf(String::isNotBlank)
        val effectiveStableKey = row.stableKey.trim()
        if (effectiveStableKey.isBlank()) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
                detail = "stableKey is blank"
            )
        }
        if (payloadStableKey != null && payloadStableKey != effectiveStableKey) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
                detail = "payload stableKey does not match the migration row"
            )
        }
        if (isUnresolvedLegacyStableKey(effectiveStableKey)) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
                detail = "legacy row has no trustworthy stable identity"
            )
        }
        if (
            payload.optJSONArray("legacyConflicts")
                ?.length()
                ?.let { conflictCount -> conflictCount > 0 } == true
        ) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.CONFLICT,
                detail = "legacy payload contains unresolved byte conflicts"
            )
        }

        val pendingRow = payload.optJSONObject("download_pending_queue")
        val cancelledRow = payload.optJSONObject("download_cancelled_key")
        if (
            cancelledRow != null &&
            pendingRow == null &&
            !legacyPayloadNeedsManagedRootSnapshot(row.payloadJson)
        ) {
            deletePayloadRow(sqliteDatabase, row.stableKey)
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.COMPLETED,
                detail = "orphan legacy cancellation marker ignored"
            )
        }
        if (pendingRow != null) {
            val hasMetadata = legacyPayloadNeedsManagedRootSnapshot(row.payloadJson)
            val operationResult = persistLegacyOperation(
                sqliteDatabase = sqliteDatabase,
                row = row,
                stableKey = effectiveStableKey,
                pendingRow = pendingRow,
                cancelled = cancelledRow != null,
                deletePayload = !hasMetadata
            )
            if (operationResult.status != LegacyDownloadUpgradeRowStatus.COMPLETED || !hasMetadata) {
                return operationResult
            }
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

        val resolvedSnapshot = snapshot ?: return LegacyDownloadUpgradeRowResult(
            stableKey = effectiveStableKey,
            status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
            detail = "managed root snapshot was not prepared"
        )

        val audio = try {
            resolveAudio(payload, resolvedSnapshot)
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
            val coverResult = materializeLegacyCoverAssets(merged)
            if (!coverResult.complete) {
                return LegacyDownloadUpgradeRowResult(
                    stableKey = effectiveStableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = "legacy cover materialization was not verified"
                )
            }
            val metadataJson = coverResult.metadata.toString()
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

    private suspend fun persistLegacyOperation(
        sqliteDatabase: androidx.sqlite.db.SupportSQLiteDatabase,
        row: PayloadRow,
        stableKey: String,
        pendingRow: JSONObject?,
        cancelled: Boolean,
        deletePayload: Boolean
    ): LegacyDownloadUpgradeRowResult {
        val song = pendingRow?.toLegacySong(stableKey)
            ?: SongItem(
                id = 0L,
                name = "legacy download",
                artist = "",
                album = "",
                albumId = 0L,
                durationMs = 0L,
                coverUrl = null,
                sourceStableKey = stableKey
            )
        return try {
            val operationId = UUID.nameUUIDFromBytes(
                "legacy-download:$stableKey".toByteArray(Charsets.UTF_8)
            ).toString()
            val request = DownloadExecutionRequest(
                operationId = operationId,
                song = song,
                userInitiated = false
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = if (cancelled) "CANCELLED" else "QUEUED",
                queueOrder = pendingRow?.optInt("queue_order", 0) ?: 0,
                database = database
            )
            if (cancelled) {
                DownloadExecutionRoomStore.updateState(
                    context = context,
                    operationId = operationId,
                    state = "CANCELLED",
                    errorCode = "LEGACY_CANCELLED",
                    database = database
                )
            }
            if (deletePayload) {
                deletePayloadRow(sqliteDatabase, row.stableKey)
            }
            LegacyDownloadUpgradeRowResult(
                stableKey = stableKey,
                status = LegacyDownloadUpgradeRowStatus.COMPLETED
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LegacyDownloadUpgradeRowResult(
                stableKey = stableKey,
                status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                detail = error.message
            )
        }
    }

    private data class LegacyCoverMaterializationResult(
        val metadata: JSONObject,
        val complete: Boolean
    )

    private suspend fun materializeLegacyCoverAssets(
        metadata: JSONObject
    ): LegacyCoverMaterializationResult {
        val restorable = metadata.optJSONObject("restorableMetadata")
            ?: return LegacyCoverMaterializationResult(metadata, complete = true)
        val baseline = restorable.optJSONObject("baseline") ?: JSONObject()
        val overrides = restorable.optJSONObject("overrides") ?: JSONObject()
        val assets = restorable.optJSONObject("assetRefs") ?: JSONObject()
        val baselineHash = assets.optString("baselineCoverHash")
            .takeIf(String::isNotBlank)
        val currentHash = assets.optString("currentCoverHash")
            .takeIf(String::isNotBlank)
        val baselineReferences = listOf(
            baseline.optString("coverReference"),
            metadata.optString("originalCoverUrl"),
            metadata.optString("coverUrl"),
            metadata.optString("coverPath")
        )
        val currentReferences = listOf(
            overrides.optString("coverReference"),
            metadata.optString("customCoverUrl"),
            metadata.optString("coverPath"),
            baselineReferences.firstOrNull(::isNonBlank)
        )
        val baselineReference = baselineReferences.firstOrNull(::isNonBlank)
        val currentReference = currentReferences.firstOrNull(::isNonBlank)
        val baselineCover = if (baselineHash == null) {
            materializeFirstAvailable(*baselineReferences.toTypedArray())
        } else {
            null
        }
        val currentCover = if (currentHash == null) {
            if (
                baselineCover != null &&
                    currentReference != null &&
                    currentReference == baselineReference
            ) {
                baselineCover
            } else {
                materializeFirstAvailable(*currentReferences.toTypedArray())
            }
        } else {
            null
        }
        val baselineNeedsMaterialization = baselineHash == null &&
            baselineReferences.any(::isMaterializableReference)
        val currentNeedsMaterialization = currentHash == null &&
            currentReferences.any(::isMaterializableReference)
        if (
            (baselineNeedsMaterialization && baselineCover == null) ||
            (currentNeedsMaterialization && currentCover == null)
        ) {
            return LegacyCoverMaterializationResult(metadata, complete = false)
        }
        baselineCover?.let { assets.put("baselineCoverHash", it.assetHash) }
        currentCover?.let {
            assets.put("currentCoverHash", it.assetHash)
            metadata.put("coverPath", it.reference)
        }
        baseline.remove("coverReference")
        overrides.remove("coverReference")
        restorable.put("baseline", baseline)
        restorable.put("overrides", overrides)
        restorable.put("assetRefs", assets)
        metadata.put("restorableMetadata", restorable)
        return LegacyCoverMaterializationResult(metadata, complete = true)
    }

    private suspend fun materializeFirstAvailable(
        vararg references: String?
    ): ManagedDownloadCoverAssetStore.MaterializedCover? {
        references.asSequence()
            .mapNotNull { reference -> reference?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { reference ->
                val materialized = ManagedDownloadCoverAssetStore.materialize(
                    context = context,
                    reference = reference
                )
                if (materialized != null) return materialized
            }
        return null
    }

    private fun isNonBlank(value: String?): Boolean = !value.isNullOrBlank()

    private fun isMaterializableReference(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return false
        return normalized.startsWith("/") ||
            normalized.startsWith("file:") ||
            normalized.startsWith("content:")
    }

    private fun resolveAudio(
        payload: JSONObject,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): ManagedDownloadStorage.StoredEntry? {
        val lookupHints = legacyAudioLookupHints(payload)
        val references = lookupHints.references
        references.forEach { reference ->
            snapshot.audioEntries.firstOrNull { entry ->
                entry.reference == reference ||
                    entry.mediaUri == reference ||
                    entry.localFilePath == reference
            }?.let { return it }
        }

        val names = lookupHints.names
        names.forEach { name ->
            val matches = snapshot.audioEntries.filter { entry -> entry.name == name }
            if (matches.size == 1) return matches.single()
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

    private fun JSONObject.toLegacySong(stableKey: String): SongItem {
        return SongItem(
            id = optLong("id", 0L),
            name = optString("name").ifBlank { "legacy download" },
            artist = optString("artist"),
            album = optString("album"),
            albumId = optLong("album_id", optLong("albumId", 0L)),
            durationMs = optLong("duration_ms", optLong("durationMs", 0L)),
            coverUrl = optString("cover_url").takeIf(String::isNotBlank),
            mediaUri = optString("media_uri").takeIf(String::isNotBlank),
            matchedLyric = optString("matched_lyric").takeIf(String::isNotBlank),
            matchedTranslatedLyric = optString("matched_translated_lyric")
                .takeIf(String::isNotBlank),
            matchedLyricSource = optString("matched_lyric_source")
                .takeIf(String::isNotBlank)
                ?.let { value -> runCatching { MusicPlatform.valueOf(value) }.getOrNull() },
            matchedSongId = optString("matched_song_id").takeIf(String::isNotBlank),
            userLyricOffsetMs = optLong(
                "user_lyric_offset_ms",
                optLong("userLyricOffsetMs", 0L)
            ),
            customCoverUrl = optString("custom_cover_url").takeIf(String::isNotBlank),
            customName = optString("custom_name").takeIf(String::isNotBlank),
            customArtist = optString("custom_artist").takeIf(String::isNotBlank),
            originalName = optString("original_name").takeIf(String::isNotBlank),
            originalArtist = optString("original_artist").takeIf(String::isNotBlank),
            originalCoverUrl = optString("original_cover_url").takeIf(String::isNotBlank),
            originalLyric = optString("original_lyric").takeIf(String::isNotBlank),
            originalTranslatedLyric = optString("original_translated_lyric")
                .takeIf(String::isNotBlank),
            localFileName = optString("local_file_name").takeIf(String::isNotBlank),
            localFilePath = optString("local_file_path").takeIf(String::isNotBlank),
            channelId = optString("channel_id").takeIf(String::isNotBlank),
            audioId = optString("audio_id").takeIf(String::isNotBlank),
            subAudioId = optString("sub_audio_id").takeIf(String::isNotBlank),
            playlistContextId = optString("playlist_context_id")
                .takeIf(String::isNotBlank),
            sourceStableKey = optString("source_stable_key")
                .takeIf(String::isNotBlank)
                ?: stableKey,
            streamUrl = optString("stream_url").takeIf(String::isNotBlank),
            matchedRomanizedLyric = optString("matched_romanized_lyric")
                .takeIf(String::isNotBlank),
            originalRomanizedLyric = optString("original_romanized_lyric")
                .takeIf(String::isNotBlank)
        )
    }

    private fun readRowBatch(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        afterStableKey: String?
    ): List<PayloadRow> {
        val query = if (afterStableKey == null) {
            "SELECT stable_key, payload_json FROM $PAYLOAD_TABLE " +
                "ORDER BY stable_key ASC LIMIT $ROW_BATCH_SIZE"
        } else {
            "SELECT stable_key, payload_json FROM $PAYLOAD_TABLE " +
                "WHERE stable_key > ? ORDER BY stable_key ASC LIMIT $ROW_BATCH_SIZE"
        }
        val cursor = if (afterStableKey == null) {
            database.query(query)
        } else {
            database.query(query, arrayOf(afterStableKey))
        }
        return cursor.use {
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

    private fun cleanLegacyProjectionTables(
        database: androidx.sqlite.db.SupportSQLiteDatabase
    ): Boolean {
        return runCatching {
            LEGACY_DOWNLOAD_PROJECTION_TABLES.forEach { tableName ->
                database.execSQL("DROP TABLE IF EXISTS `$tableName`")
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        internal const val PAYLOAD_TABLE = "legacy_download_upgrade_payload"
        private const val ROW_BATCH_SIZE = 64
        private val LEGACY_DOWNLOAD_PROJECTION_TABLES = listOf(
            "download_pending_queue",
            "download_cancelled_key",
            "downloaded_song_catalog",
            "download_snapshot_entry",
            "download_snapshot_metadata",
            "managed_download_artifact"
        )
    }
}

internal data class LegacyAudioLookupHints(
    val references: List<String>,
    val names: List<String>
)

internal fun isUnresolvedLegacyStableKey(stableKey: String): Boolean {
    val normalized = stableKey.trim()
    return normalized.startsWith("legacy:") ||
        normalized.startsWith("legacy-snapshot:")
}

internal fun legacyAudioLookupHints(payload: JSONObject): LegacyAudioLookupHints {
    val references = linkedSetOf<String>()
    val names = linkedSetOf<String>()

    fun addReference(value: String?) {
        value?.trim()?.takeIf(String::isNotBlank)?.let(references::add)
    }

    fun addName(value: String?) {
        value?.trim()?.takeIf(String::isNotBlank)?.let(names::add)
    }

    addReference(payload.optString("mediaUri"))
    addReference(payload.optString("media_uri"))
    addReference(payload.optString("filePath"))
    addReference(payload.optString("file_path"))
    addReference(payload.optString("sourceMediaUri"))
    addReference(payload.optString("audioReference"))
    addReference(payload.optString("audio_reference"))

    addName(payload.optString("audioFileName"))
    addName(payload.optString("audio_file_name"))
    addName(payload.optString("audioName"))
    addName(payload.optString("audio_name"))
    addName(payload.optString("name"))
    payload.optString("filePath")
        .takeIf(String::isNotBlank)
        ?.let { path -> addName(File(path).name) }
    payload.optString("file_path")
        .takeIf(String::isNotBlank)
        ?.let { path -> addName(File(path).name) }

    payload.optJSONArray("download_snapshot_entries")?.let { entries ->
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            addReference(entry.optString("reference"))
            addReference(entry.optString("media_uri"))
            addReference(entry.optString("mediaUri"))
            addReference(entry.optString("file_path"))
            addReference(entry.optString("filePath"))
            addName(entry.optString("name"))
            addName(entry.optString("audio_name"))
            addName(entry.optString("audioName"))
        }
    }
    return LegacyAudioLookupHints(
        references = references.toList(),
        names = names.toList()
    )
}

internal fun legacyPayloadNeedsManagedRootSnapshot(payloadJson: String): Boolean {
    val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return true
    val hasPending = payload.optJSONObject("download_pending_queue") != null
    val hasCancelled = payload.optJSONObject("download_cancelled_key") != null
    val hasManagedMetadata = MANAGED_ROOT_PAYLOAD_KEYS.any { key ->
        payload.has(key) && !payload.isNull(key)
    }
    if (hasPending) return hasManagedMetadata
    if (hasCancelled) return hasManagedMetadata
    return true
}

private val MANAGED_ROOT_PAYLOAD_KEYS = setOf(
    "downloaded_song_catalog",
    "download_snapshot_metadata",
    "download_snapshot_entries",
    "managed_download_artifact",
    "restorableMetadata",
    "legacyConflicts"
)
