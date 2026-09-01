package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONArray
import org.json.JSONObject

internal enum class LegacyDownloadUpgradeRowStatus {
    COMPLETED,
    QUARANTINED,
    AUDIO_NOT_FOUND,
    STORAGE_UNAVAILABLE,
    PROVIDER_FAILURE,
    INVALID_PAYLOAD,
    CONFLICT,
    QUEUE_IMPORT_SUPPRESSED
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
    val legacyProjectionTablesCleaned: Boolean,
    val rowsQuarantined: Int = 0,
    val rowsSuppressedByUserClear: Int = 0
) {
    val isComplete: Boolean
        get() = rowsPending == 0 && temporaryTableCleaned && legacyProjectionTablesCleaned

    val isUserClearSuppressed: Boolean
        get() = rowsSuppressedByUserClear > 0 && rowsPending == rowsSuppressedByUserClear

    val isSettled: Boolean
        get() = isComplete || isUserClearSuppressed
}

internal fun resolveLegacyManagedCoverEntry(
    reference: String?,
    persistedFileName: String?,
    coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>
): ManagedDownloadStorage.StoredEntry? {
    return LegacyManagedRootLookup(
        audioEntries = emptyList(),
        coverEntriesByName = coverEntriesByName
    ).resolveCover(reference, persistedFileName)
}

internal fun legacyManagedCoverFileNameHint(reference: String?): String? {
    val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return null
    val isDocumentReference = normalized.startsWith("content:", ignoreCase = true) &&
        normalized.contains("/document/", ignoreCase = true)
    val isFileReference = normalized.startsWith("file:", ignoreCase = true) ||
        normalized.startsWith("/")
    if (!isDocumentReference && !isFileReference) return null
    val withoutQuery = normalized.substringBefore('?').substringBefore('#')
    val decoded = runCatching {
        URLDecoder.decode(
            withoutQuery.replace("+", "%2B"),
            Charsets.UTF_8.name()
        )
    }.getOrNull() ?: return null
    val normalizedPath = decoded.replace('\\', '/')
    val marker = "/Covers/"
    val markerIndex = normalizedPath.lastIndexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    return normalizedPath
        .substring(markerIndex + marker.length)
        .takeIf(::isSafeLegacyManagedFileName)
}

internal class LegacyManagedRootLookup(
    audioEntries: List<ManagedDownloadStorage.StoredEntry>,
    coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>
) {
    constructor(snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot) : this(
        audioEntries = snapshot.audioEntries,
        coverEntriesByName = snapshot.coverEntriesByName
    )

    private val audioByReference = uniqueEntryIndex(
        audioEntries.flatMap { entry ->
            listOfNotNull(
                entry.reference.takeIf(String::isNotBlank),
                entry.mediaUri.takeIf(String::isNotBlank),
                entry.localFilePath?.takeIf(String::isNotBlank)
            ).map { alias -> alias to entry }
        }
    )
    private val audioByName = uniqueEntryIndex(
        audioEntries.map { entry -> entry.name to entry }
    )
    private val coverByReference = uniqueEntryIndex(
        coverEntriesByName.values.flatMap { entry ->
            listOfNotNull(
                entry.reference.takeIf(String::isNotBlank),
                entry.mediaUri.takeIf(String::isNotBlank),
                entry.localFilePath?.takeIf(String::isNotBlank)
            ).map { alias -> alias to entry }
        }
    )
    private val coverByCanonicalName = uniqueEntryIndex(
        coverEntriesByName.values.map { entry -> canonicalLegacyName(entry.name) to entry }
    )

    fun resolveAudio(payload: JSONObject): ManagedDownloadStorage.StoredEntry? {
        val hints = legacyAudioLookupHints(payload)
        hints.references.forEach { reference ->
            audioByReference[reference]?.let { return it }
        }
        hints.names.forEach { name ->
            audioByName[name]?.let { return it }
        }
        return null
    }

    fun resolveCover(
        reference: String?,
        persistedFileName: String?
    ): ManagedDownloadStorage.StoredEntry? {
        reference?.trim()?.takeIf(String::isNotBlank)?.let { normalizedReference ->
            coverByReference[normalizedReference]?.let { return it }
        }
        val fileName = persistedFileName
            ?.trim()
            ?.takeIf(::isSafeLegacyManagedFileName)
            ?: legacyManagedCoverFileNameHint(reference)
            ?: return null
        return coverByCanonicalName[canonicalLegacyName(fileName)]
    }
}

private fun isSafeLegacyManagedFileName(name: String): Boolean {
    return name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        '/' !in name &&
        '\\' !in name
}

private fun canonicalLegacyName(name: String): String {
    return Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

private fun uniqueEntryIndex(
    entries: List<Pair<String, ManagedDownloadStorage.StoredEntry>>
): Map<String, ManagedDownloadStorage.StoredEntry> {
    val unique = mutableMapOf<String, ManagedDownloadStorage.StoredEntry>()
    val ambiguous = mutableSetOf<String>()
    entries.forEach { (key, entry) ->
        if (key in ambiguous) return@forEach
        val previous = unique[key]
        if (previous == null || previous == entry) {
            unique[key] = entry
        } else {
            unique.remove(key)
            ambiguous += key
        }
    }
    return unique
}

internal fun legacyMetadataStructurallyEquals(
    existing: JSONObject?,
    upgraded: JSONObject
): Boolean {
    return existing != null && canonicalLegacyJson(existing) == canonicalLegacyJson(upgraded)
}

private fun canonicalLegacyJson(value: Any?): String {
    return when (value) {
        null,
        JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { key ->
            "${JSONObject.quote(key)}:${canonicalLegacyJson(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { index -> canonicalLegacyJson(value.opt(index)) }
        is String -> JSONObject.quote(value)
        is Number,
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}

internal fun selectLegacyRestorableCoverReference(
    existingReference: String?,
    sourceReference: String?,
    existingReferenceIsManaged: Boolean
): String? {
    val existing = existingReference?.trim()?.takeIf(String::isNotBlank)
    val source = sourceReference?.trim()?.takeIf(String::isNotBlank)
    return when {
        existing == null -> source
        existingReferenceIsManaged && source != null -> source
        else -> existing
    }
}

/**
 * 把 v15 迁移留下的一次性 payload 逐行落到托管 root
 */
internal class LegacyDownloadUpgradeCoordinator(
    private val context: Context,
    private val database: NeriUserDataDatabase =
        NeriUserDataDatabase.getInstance(context.applicationContext)
) {
    suspend fun execute(
        onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): LegacyDownloadUpgradeResult = withContext(Dispatchers.IO) {
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

        var queueImportSuppressed = isLegacyQueueImportSuppressed()
        val unsuppressedRows = countPayloadRows(
            database = sqliteDatabase,
            excludeUserClearSuppressedRows = queueImportSuppressed
        )
        if (queueImportSuppressed && unsuppressedRows == 0) {
            val rowsSuppressedByUserClear = countUserClearSuppressedPayloadRows(sqliteDatabase)
            if (rowsSuppressedByUserClear > 0) {
                return@withContext LegacyDownloadUpgradeResult(
                    tableFound = true,
                    rowsSeen = 0,
                    rowsCompleted = 0,
                    rowsPending = rowsSuppressedByUserClear,
                    rowResults = emptyList(),
                    temporaryTableCleaned = false,
                    legacyProjectionTablesCleaned = false,
                    rowsSuppressedByUserClear = rowsSuppressedByUserClear
                )
            }
        }
        var rowsQuarantined = quarantineUnresolvedPayloadRows(sqliteDatabase)
        val totalRows = countPayloadRows(
            database = sqliteDatabase,
            excludeUserClearSuppressedRows = queueImportSuppressed
        )
        var rowsSeen = 0
        val rowResults = mutableListOf<LegacyDownloadUpgradeRowResult>()
        var afterStableKey: String? = null
        var managedSnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null
        var managedLookup: LegacyManagedRootLookup? = null
        var snapshotFailure: Throwable? = null
        while (true) {
            val rows = readRowBatch(
                database = sqliteDatabase,
                afterStableKey = afterStableKey,
                excludeUserClearSuppressedRows = queueImportSuppressed
            )
            if (rows.isEmpty()) break
            val preparedRows = rows.map { row ->
                val parsed = runCatching { JSONObject(row.payloadJson) }
                PreparedPayloadRow(
                    row = row,
                    payload = parsed.getOrNull(),
                    parseFailureDetail = parsed.exceptionOrNull()?.message
                )
            }
            val snapshotRequirements = preparedRows.map { prepared ->
                prepared.payload?.let(::legacyPayloadNeedsManagedRootSnapshot) == true
            }
            if (
                snapshotRequirements.any { required -> required } &&
                managedSnapshot == null &&
                snapshotFailure == null
            ) {
                runCatching {
                    ManagedDownloadStorage.buildLegacyUpgradeSnapshot(context)
                }.onSuccess { snapshot ->
                    managedSnapshot = snapshot
                    managedLookup = LegacyManagedRootLookup(snapshot)
                    ManagedDownloadStorage.prepareLegacyMetadataUpgrade(context)
                }.onFailure { error ->
                    snapshotFailure = error
                }
            }
            val batchStart = rowsSeen
            val batchResults = coroutineScope {
                val permits = Semaphore(ROW_PROCESS_PARALLELISM)
                val pendingResults = List(preparedRows.size) {
                    CompletableDeferred<LegacyDownloadUpgradeRowResult>()
                }
                val lanes = preparedRows.indices.groupBy { index ->
                    rowProcessingLane(
                        prepared = preparedRows[index],
                        needsSnapshot = snapshotRequirements[index],
                        lookup = managedLookup,
                        fallbackIndex = index
                    )
                }
                val laneJobs = lanes.values.map { laneIndexes ->
                    async {
                        permits.withPermit {
                            laneIndexes.forEach { index ->
                                pendingResults[index].complete(
                                    processPreparedRow(
                                        prepared = preparedRows[index],
                                        needsSnapshot = snapshotRequirements[index],
                                        snapshot = managedSnapshot,
                                        lookup = managedLookup,
                                        snapshotFailure = snapshotFailure
                                    )
                                )
                            }
                        }
                    }
                }
                buildList {
                    pendingResults.forEachIndexed { index, pending ->
                        add(pending.await())
                        val processed = batchStart + index + 1
                        if (
                            processed % PROGRESS_UPDATE_INTERVAL == 0 ||
                            index == pendingResults.lastIndex
                        ) {
                            onProgress(processed, totalRows)
                        }
                    }
                }
                    .also { laneJobs.awaitAll() }
            }
            val suppressedKeys = persistUserClearSuppressedPayloadRows(
                batchResults.asSequence()
                    .filter { result ->
                        result.status == LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED
                    }
                    .map(LegacyDownloadUpgradeRowResult::stableKey)
                    .toSet()
            )
            if (suppressedKeys.isNotEmpty()) {
                queueImportSuppressed = true
            }
            val persistedResults = batchResults.map { result ->
                if (
                    result.status == LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED &&
                        result.stableKey !in suppressedKeys
                ) {
                    result.copy(
                        status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                        detail = "user clear suppression marker was not persisted"
                    )
                } else {
                    result
                }
            }
            val quarantinedKeys = quarantineTerminalPayloadRows(
                database = sqliteDatabase,
                rows = rows,
                results = persistedResults
            )
            rowsQuarantined += quarantinedKeys.size
            val settledResults = persistedResults.map { result ->
                if (result.stableKey in quarantinedKeys) {
                    result.copy(status = LegacyDownloadUpgradeRowStatus.QUARANTINED)
                } else {
                    result
                }
            }
            deleteSettledPayloadRows(
                database = sqliteDatabase,
                stableKeys = settledResults.asSequence()
                    .filter { result ->
                        result.status == LegacyDownloadUpgradeRowStatus.COMPLETED ||
                            result.status == LegacyDownloadUpgradeRowStatus.QUARANTINED
                    }
                    .map(LegacyDownloadUpgradeRowResult::stableKey)
                    .toList()
            )
            rowsSeen += rows.size
            rowResults += settledResults
            afterStableKey = rows.last().stableKey
        }
        if (rowsSeen == 0) {
            val rowsSuppressedByUserClear = if (queueImportSuppressed) {
                countUserClearSuppressedPayloadRows(sqliteDatabase)
            } else {
                0
            }
            if (rowsSuppressedByUserClear > 0) {
                return@withContext LegacyDownloadUpgradeResult(
                    tableFound = true,
                    rowsSeen = 0,
                    rowsCompleted = 0,
                    rowsPending = rowsSuppressedByUserClear,
                    rowResults = emptyList(),
                    temporaryTableCleaned = false,
                    legacyProjectionTablesCleaned = false,
                    rowsQuarantined = rowsQuarantined,
                    rowsSuppressedByUserClear = rowsSuppressedByUserClear
                )
            }
            val temporaryTableCleaned = cleanTemporaryTable(sqliteDatabase)
            return@withContext LegacyDownloadUpgradeResult(
                tableFound = true,
                rowsSeen = 0,
                rowsCompleted = 0,
                rowsPending = 0,
                rowResults = emptyList(),
                temporaryTableCleaned = temporaryTableCleaned,
                legacyProjectionTablesCleaned = temporaryTableCleaned &&
                    cleanLegacyProjectionTables(sqliteDatabase),
                rowsQuarantined = rowsQuarantined
            )
        }
        return@withContext finishResult(
            database = sqliteDatabase,
            rowsSeen = rowsSeen,
            rowResults = rowResults,
            rowsQuarantined = rowsQuarantined,
            queueImportSuppressed = queueImportSuppressed
        )
    }

    private suspend fun processPreparedRow(
        prepared: PreparedPayloadRow,
        needsSnapshot: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        lookup: LegacyManagedRootLookup?,
        snapshotFailure: Throwable?
    ): LegacyDownloadUpgradeRowResult {
        val payload = prepared.payload ?: return LegacyDownloadUpgradeRowResult(
            stableKey = prepared.row.stableKey,
            status = LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD,
            detail = prepared.parseFailureDetail
        )
        if (needsSnapshot && snapshotFailure != null) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = prepared.row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                detail = snapshotFailure.message
            )
        }
        if (needsSnapshot && snapshot?.rootEntriesComplete == false) {
            return LegacyDownloadUpgradeRowResult(
                stableKey = prepared.row.stableKey,
                status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                detail = "managed root enumeration was incomplete"
            )
        }
        return processRow(
            row = prepared.row,
            payload = payload,
            snapshot = if (needsSnapshot) snapshot else null,
            lookup = if (needsSnapshot) lookup else null
        )
    }

    private fun rowProcessingLane(
        prepared: PreparedPayloadRow,
        needsSnapshot: Boolean,
        lookup: LegacyManagedRootLookup?,
        fallbackIndex: Int
    ): String {
        if (!needsSnapshot) return "row:$fallbackIndex"
        val payload = prepared.payload ?: return "row:$fallbackIndex"
        return runCatching { lookup?.resolveAudio(payload)?.reference }
            .getOrNull()
            ?.let { reference -> "audio:$reference" }
            ?: "row:$fallbackIndex"
    }

    suspend fun requeueResolvableQuarantinedRows(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Int = withContext(Dispatchers.IO) {
        val sqliteDatabase = database.openHelper.writableDatabase
        if (!tableExists(sqliteDatabase, QUARANTINE_TABLE)) return@withContext 0
        val lookup = LegacyManagedRootLookup(snapshot)
        val candidates = readQuarantinedPayloadRows(sqliteDatabase).mapNotNull { row ->
            val payload = runCatching { JSONObject(row.payloadJson) }.getOrNull()
                ?: return@mapNotNull null
            if (payload.optJSONArray("legacyConflicts")?.length()?.let { it > 0 } == true) {
                return@mapNotNull null
            }
            val audio = lookup.resolveAudio(payload) ?: return@mapNotNull null
            val canonicalStableKey = snapshot.metadataByAudioName[audio.logicalName]
                ?.stableKey
                ?.trim()
                ?.takeIf { stableKey ->
                    stableKey.isNotBlank() && !isUnresolvedLegacyStableKey(stableKey)
                }
                ?: return@mapNotNull null
            val canonicalPayload = JSONObject(payload.toString()).apply {
                put("legacyQuarantineStableKey", row.stableKey)
                put("stableKey", canonicalStableKey)
            }.toString()
            QuarantineRequeueCandidate(
                quarantinedStableKey = row.stableKey,
                quarantinedPayloadJson = row.payloadJson,
                canonicalStableKey = canonicalStableKey,
                canonicalPayloadJson = canonicalPayload
            )
        }
        val uniqueCandidates = candidates.groupBy(QuarantineRequeueCandidate::canonicalStableKey)
            .values
            .mapNotNull { matches -> matches.singleOrNull() }
        if (uniqueCandidates.isEmpty()) return@withContext 0

        createPayloadTable(sqliteDatabase)
        var restored = 0
        sqliteDatabase.beginTransaction()
        try {
            uniqueCandidates.forEach { candidate ->
                sqliteDatabase.execSQL(
                    "INSERT OR IGNORE INTO $PAYLOAD_TABLE (stable_key, payload_json) " +
                        "VALUES (?, ?)",
                    arrayOf(candidate.canonicalStableKey, candidate.canonicalPayloadJson)
                )
                val inserted = sqliteDatabase.query(
                    "SELECT payload_json FROM $PAYLOAD_TABLE WHERE stable_key = ? LIMIT 1",
                    arrayOf(candidate.canonicalStableKey)
                ).use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getString(0) == candidate.canonicalPayloadJson
                }
                if (!inserted) return@forEach
                sqliteDatabase.execSQL(
                    "DELETE FROM $QUARANTINE_TABLE " +
                        "WHERE stable_key = ? AND payload_json = ?",
                    arrayOf(
                        candidate.quarantinedStableKey,
                        candidate.quarantinedPayloadJson
                    )
                )
                restored += 1
            }
            sqliteDatabase.setTransactionSuccessful()
        } finally {
            sqliteDatabase.endTransaction()
        }
        restored
    }

    private fun finishResult(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        rowsSeen: Int,
        rowResults: List<LegacyDownloadUpgradeRowResult>,
        rowsQuarantined: Int,
        queueImportSuppressed: Boolean
    ): LegacyDownloadUpgradeResult {
        val retryableRows = rowResults.count { result ->
            result.status != LegacyDownloadUpgradeRowStatus.COMPLETED &&
                result.status != LegacyDownloadUpgradeRowStatus.QUARANTINED &&
                result.status != LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED
        }
        val rowsSuppressedByUserClear = if (queueImportSuppressed) {
            countUserClearSuppressedPayloadRows(database)
        } else {
            0
        }
        val pending = retryableRows + rowsSuppressedByUserClear
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
            legacyProjectionTablesCleaned = legacyProjectionTablesCleaned,
            rowsQuarantined = rowsQuarantined,
            rowsSuppressedByUserClear = rowsSuppressedByUserClear
        )
    }

    private suspend fun processRow(
        row: PayloadRow,
        payload: JSONObject,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        lookup: LegacyManagedRootLookup?
    ): LegacyDownloadUpgradeRowResult {
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
            return LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = LegacyDownloadUpgradeRowStatus.COMPLETED,
                detail = "orphan legacy cancellation marker ignored"
            )
        }
        var queueImportSuppressed = false
        if (pendingRow != null) {
            val hasMetadata = legacyPayloadNeedsManagedRootSnapshot(row.payloadJson)
            val operationResult = persistLegacyOperation(
                stableKey = effectiveStableKey,
                pendingRow = pendingRow,
                cancelled = cancelledRow != null
            )
            queueImportSuppressed = operationResult.status ==
                LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED
            if (
                (
                    operationResult.status != LegacyDownloadUpgradeRowStatus.COMPLETED &&
                        !queueImportSuppressed
                    ) || !hasMetadata
            ) {
                return operationResult
            }
        }

        val resolvedSnapshot = snapshot ?: return LegacyDownloadUpgradeRowResult(
            stableKey = effectiveStableKey,
            status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
            detail = "managed root snapshot was not prepared"
        )
        val resolvedLookup = lookup ?: return LegacyDownloadUpgradeRowResult(
            stableKey = effectiveStableKey,
            status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
            detail = "managed root lookup was not prepared"
        )

        val audio = try {
            resolvedLookup.resolveAudio(payload)
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
            val existingEntry = resolvedSnapshot.metadataEntriesByAudioName[audio.logicalName]
            val cachedExistingJson = resolvedSnapshot.metadataByAudioName[audio.logicalName]
                ?.let(ManagedDownloadStorageJsonCodec::downloadedAudioMetadataToJson)
            if (cachedExistingJson != null) {
                val cachedCoverResult = buildUpgradedMetadata(
                    payload = payload,
                    existing = cachedExistingJson,
                    audio = audio,
                    stableKey = effectiveStableKey,
                    snapshot = resolvedSnapshot,
                    lookup = resolvedLookup
                )
                if (
                    cachedCoverResult.complete &&
                    legacyMetadataStructurallyEquals(
                        cachedExistingJson,
                        cachedCoverResult.metadata
                    )
                ) {
                    return LegacyDownloadUpgradeRowResult(
                        stableKey = effectiveStableKey,
                        status = if (queueImportSuppressed) {
                            LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED
                        } else {
                            LegacyDownloadUpgradeRowStatus.COMPLETED
                        }
                    )
                }
            }
            val existingJson = existingEntry
                ?.let { ManagedDownloadStorage.readText(context, it.reference) }
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            val coverResult = buildUpgradedMetadata(
                payload = payload,
                existing = existingJson,
                audio = audio,
                stableKey = effectiveStableKey,
                snapshot = resolvedSnapshot,
                lookup = resolvedLookup
            )
            if (!coverResult.complete) {
                return LegacyDownloadUpgradeRowResult(
                    stableKey = effectiveStableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = "legacy cover materialization was not verified"
                )
            }
            val metadataJson = coverResult.metadata.toString()
            if (
                !legacyMetadataStructurallyEquals(existingJson, coverResult.metadata) &&
                !ManagedDownloadStorage.saveMetadataForLegacyUpgrade(
                    context = context,
                    audio = audio,
                    json = metadataJson,
                    expectedAbsent = existingEntry == null,
                    knownMetadataEntry = existingEntry
                )
            ) {
                return LegacyDownloadUpgradeRowResult(
                    stableKey = effectiveStableKey,
                    status = LegacyDownloadUpgradeRowStatus.PROVIDER_FAILURE,
                    detail = "metadata write was not verified"
                )
            }
            LegacyDownloadUpgradeRowResult(
                stableKey = effectiveStableKey,
                status = if (queueImportSuppressed) {
                    LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED
                } else {
                    LegacyDownloadUpgradeRowStatus.COMPLETED
                }
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

    private suspend fun buildUpgradedMetadata(
        payload: JSONObject,
        existing: JSONObject?,
        audio: ManagedDownloadStorage.StoredEntry,
        stableKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        lookup: LegacyManagedRootLookup
    ): LegacyCoverMaterializationResult {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = payload,
            existing = existing,
            audioFileName = audio.logicalName
        ).apply {
            put("stableKey", stableKey)
            put("audioFileName", audio.logicalName)
            put("mediaUri", audio.mediaUri)
            if (audio.localFilePath != null) {
                put("localFilePath", audio.localFilePath)
            }
        }
        return materializeLegacyCoverAssets(
            metadata = merged,
            snapshot = snapshot,
            lookup = lookup
        )
    }

    private suspend fun persistLegacyOperation(
        stableKey: String,
        pendingRow: JSONObject?,
        cancelled: Boolean
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
            database.withTransaction {
                if (isLegacyQueueImportSuppressed()) {
                    return@withTransaction LegacyDownloadUpgradeRowResult(
                        stableKey = stableKey,
                        status = LegacyDownloadUpgradeRowStatus.QUEUE_IMPORT_SUPPRESSED
                    )
                }
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
                LegacyDownloadUpgradeRowResult(
                    stableKey = stableKey,
                    status = LegacyDownloadUpgradeRowStatus.COMPLETED
                )
            }
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

    private fun rebindLegacyManagedCoverReferences(
        metadata: JSONObject,
        lookup: LegacyManagedRootLookup
    ) {
        val restorable = metadata.optJSONObject("restorableMetadata")
            ?: JSONObject().also { created ->
                metadata.put("restorableMetadata", created)
            }
        val assets = restorable.optJSONObject("assetRefs")
            ?: JSONObject().also { created -> restorable.put("assetRefs", created) }
        val recoveryReferences = assets.optJSONArray("legacyCoverRecoveryReferences")
            ?: JSONArray().also { created ->
                assets.put("legacyCoverRecoveryReferences", created)
            }
        val containers = listOfNotNull(
            metadata to listOf("coverPath", "coverUrl", "customCoverUrl", "originalCoverUrl"),
            restorable.optJSONObject("baseline")?.let { baseline ->
                baseline to listOf("coverReference")
            },
            restorable.optJSONObject("overrides")?.let { overrides ->
                overrides to listOf("coverReference")
            }
        )
        containers.forEach { (container, keys) ->
            keys.forEach { key ->
                val reference = container.optString(key).takeIf(String::isNotBlank)
                    ?: return@forEach
                val fileName = legacyManagedCoverFileNameHint(reference)
                    ?: return@forEach
                val currentEntry = lookup.resolveCover(reference, fileName)
                if (currentEntry == null) {
                    val alreadyStored = (0 until recoveryReferences.length()).any { index ->
                        recoveryReferences.optString(index) == reference
                    }
                    if (!alreadyStored) recoveryReferences.put(reference)
                    container.remove(key)
                } else {
                    container.put(key, currentEntry.reference)
                }
            }
        }
    }

    private suspend fun materializeLegacyCoverAssets(
        metadata: JSONObject,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        lookup: LegacyManagedRootLookup
    ): LegacyCoverMaterializationResult {
        rebindLegacyManagedCoverReferences(metadata, lookup)
        val restorable = metadata.optJSONObject("restorableMetadata")
            ?: return LegacyCoverMaterializationResult(metadata, complete = true)
        val baseline = restorable.optJSONObject("baseline") ?: JSONObject()
        val overrides = restorable.optJSONObject("overrides") ?: JSONObject()
        val assets = restorable.optJSONObject("assetRefs") ?: JSONObject()
        val baselineHash = assets.optString("baselineCoverHash")
            .takeIf(String::isNotBlank)
        val currentHash = assets.optString("currentCoverHash")
            .takeIf(String::isNotBlank)
        val baselineFileName = assets.optString("baselineCoverFileName")
            .takeIf(String::isNotBlank)
        val currentFileName = assets.optString("currentCoverFileName")
            .takeIf(String::isNotBlank)
        val customCoverSource = metadata.optString("customCoverUrl")
            .takeIf(String::isNotBlank)
        val baselineCoverSource = listOf(
            metadata.optString("originalCoverUrl"),
            metadata.optString("coverUrl").takeUnless { cover ->
                cover.isBlank() || cover == customCoverSource
            }
        ).firstOrNull(::isNonBlank)
        val existingBaselineReference = baseline.optString("coverReference")
            .takeIf(String::isNotBlank)
        val existingCurrentReference = overrides.optString("coverReference")
            .takeIf(String::isNotBlank)
        selectLegacyRestorableCoverReference(
            existingReference = existingBaselineReference,
            sourceReference = baselineCoverSource,
            existingReferenceIsManaged = lookup.resolveCover(
                existingBaselineReference,
                persistedFileName = null
            ) != null
        )?.let { reference -> baseline.put("coverReference", reference) }
        selectLegacyRestorableCoverReference(
            existingReference = existingCurrentReference,
            sourceReference = customCoverSource,
            existingReferenceIsManaged = lookup.resolveCover(
                existingCurrentReference,
                persistedFileName = null
            ) != null
        )?.let { reference -> overrides.put("coverReference", reference) }
        val baselineReferences = listOf(
            baseline.optString("coverReference"),
            metadata.optString("originalCoverUrl"),
            metadata.optString("coverUrl"),
            metadata.optString("coverPath").takeIf { customCoverSource == null }
        )
        val currentReferences = listOf(
            overrides.optString("coverReference"),
            metadata.optString("customCoverUrl"),
            metadata.optString("coverPath"),
            baselineReferences.firstOrNull(::isNonBlank)
        )
        val baselineReference = baselineReferences.firstOrNull(::isNonBlank)
        val currentReference = currentReferences.firstOrNull(::isNonBlank)
        val baselineCover = when {
            baselineHash == null -> materializeFirstAvailable(
                lookup = lookup,
                persistedFileName = baselineFileName,
                references = baselineReferences
            )
            baselineFileName == null -> fingerprintFirstManagedAvailable(
                snapshot = snapshot,
                lookup = lookup,
                expectedHash = baselineHash,
                references = baselineReferences
            )
            else -> null
        }
        val currentCover = if (currentHash == null || currentFileName == null) {
            if (
                baselineCover != null &&
                    currentReference != null &&
                    currentReference == baselineReference &&
                    (currentHash == null ||
                        currentHash.equals(baselineCover.assetHash, ignoreCase = true))
            ) {
                baselineCover
            } else if (currentHash == null) {
                materializeFirstAvailable(
                    lookup = lookup,
                    persistedFileName = currentFileName,
                    references = currentReferences
                )
            } else {
                fingerprintFirstManagedAvailable(
                    snapshot = snapshot,
                    lookup = lookup,
                    expectedHash = currentHash,
                    references = currentReferences
                )
            }
        } else {
            null
        }
        val baselineNeedsMaterialization = baselineHash == null &&
            baselineReferences.any(::isMaterializableReference)
        val currentNeedsMaterialization = currentHash == null &&
            currentReferences.any(::isMaterializableReference)
        val baselineNeedsFileName = baselineHash != null &&
            baselineFileName == null &&
            baselineReferences.any(::isMaterializableReference)
        val currentNeedsFileName = currentHash != null &&
            currentFileName == null &&
            currentReferences.any(::isMaterializableReference)
        if (
            (baselineNeedsMaterialization && baselineCover == null) ||
            (currentNeedsMaterialization && currentCover == null) ||
            (baselineNeedsFileName && baselineCover == null) ||
            (currentNeedsFileName && currentCover == null)
        ) {
            return LegacyCoverMaterializationResult(metadata, complete = false)
        }
        baselineCover?.let {
            assets.put("baselineCoverHash", it.assetHash)
            it.fileName?.let { fileName -> assets.put("baselineCoverFileName", fileName) }
        }
        currentCover?.let {
            assets.put("currentCoverHash", it.assetHash)
            it.fileName?.let { fileName -> assets.put("currentCoverFileName", fileName) }
            metadata.put("coverPath", it.reference)
        }
        restorable.put("baseline", baseline)
        restorable.put("overrides", overrides)
        restorable.put("assetRefs", assets)
        metadata.put("restorableMetadata", restorable)
        return LegacyCoverMaterializationResult(metadata, complete = true)
    }

    private suspend fun materializeFirstAvailable(
        lookup: LegacyManagedRootLookup,
        persistedFileName: String?,
        references: List<String?>
    ): ManagedDownloadCoverAssetStore.MaterializedCover? {
        references.asSequence()
            .mapNotNull { reference -> reference?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { reference ->
                val managedEntry = lookup.resolveCover(reference, persistedFileName)
                val materialized = if (managedEntry != null) {
                    ManagedDownloadCoverAssetStore.materialize(
                        context = context,
                        reference = managedEntry.reference,
                        preferredFileName = null
                    )
                } else {
                    ManagedDownloadCoverAssetStore.materializeLegacyReadable(
                        context = context,
                        reference = reference
                    )
                }
                if (materialized != null) return materialized
            }
        return null
    }

    private suspend fun fingerprintFirstManagedAvailable(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        lookup: LegacyManagedRootLookup,
        expectedHash: String,
        references: List<String?>
    ): ManagedDownloadCoverAssetStore.MaterializedCover? {
        val managedEntries = references.asSequence()
            .mapNotNull { reference ->
                lookup.resolveCover(reference, persistedFileName = null)
            }
            .plus(
                snapshot.coverEntriesByName.values.asSequence().filter { entry ->
                    entry.name.substringBeforeLast('.', entry.name)
                        .equals(expectedHash, ignoreCase = true)
                }
            )
            .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        return fingerprintFirstMatchingManagedCover(
            managedEntries = managedEntries,
            expectedHash = expectedHash
        ) { managedEntry ->
            ManagedDownloadCoverAssetStore.materialize(
                context = context,
                reference = managedEntry.reference,
                preferredFileName = null
            )
        }
    }

    private fun isNonBlank(value: String?): Boolean = !value.isNullOrBlank()

    private fun isMaterializableReference(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return false
        return normalized.startsWith("/") ||
            normalized.startsWith("file:") ||
            normalized.startsWith("content:")
    }

    private data class PayloadRow(
        val stableKey: String,
        val payloadJson: String
    )

    private data class PreparedPayloadRow(
        val row: PayloadRow,
        val payload: JSONObject?,
        val parseFailureDetail: String?
    )

    private data class QuarantineRequeueCandidate(
        val quarantinedStableKey: String,
        val quarantinedPayloadJson: String,
        val canonicalStableKey: String,
        val canonicalPayloadJson: String
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
        afterStableKey: String?,
        excludeUserClearSuppressedRows: Boolean
    ): List<PayloadRow> {
        val conditions = mutableListOf<String>()
        val bindArgs = mutableListOf<Any>()
        afterStableKey?.let { stableKey ->
            conditions += "payload.stable_key > ?"
            bindArgs += stableKey
        }
        if (excludeUserClearSuppressedRows) {
            conditions +=
                "NOT EXISTS (SELECT 1 FROM migration_metadata marker " +
                    "WHERE marker.key = ? || payload.stable_key AND marker.value = ?)"
            bindArgs += USER_CLEAR_SUPPRESSION_METADATA_KEY_PREFIX
            bindArgs += DownloadRecoveryRoomStore.USER_CLEARED_STATE
        }
        val whereClause = conditions.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " WHERE ", separator = " AND ")
            .orEmpty()
        val query = "SELECT stable_key, payload_json FROM $PAYLOAD_TABLE payload" +
            whereClause +
            " ORDER BY payload.stable_key ASC LIMIT $ROW_BATCH_SIZE"
        val cursor = if (bindArgs.isEmpty()) {
            database.query(query)
        } else {
            database.query(query, bindArgs.toTypedArray())
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

    private fun countPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        excludeUserClearSuppressedRows: Boolean
    ): Int {
        val query = if (excludeUserClearSuppressedRows) {
            "SELECT COUNT(*) FROM $PAYLOAD_TABLE payload " +
                "WHERE NOT EXISTS (SELECT 1 FROM migration_metadata marker " +
                "WHERE marker.key = ? || payload.stable_key AND marker.value = ?)"
        } else {
            "SELECT COUNT(*) FROM $PAYLOAD_TABLE"
        }
        val cursor = if (excludeUserClearSuppressedRows) {
            database.query(
                query,
                arrayOf(
                    USER_CLEAR_SUPPRESSION_METADATA_KEY_PREFIX,
                    DownloadRecoveryRoomStore.USER_CLEARED_STATE
                )
            )
        } else {
            database.query(query)
        }
        return cursor.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private suspend fun persistUserClearSuppressedPayloadRows(
        stableKeys: Set<String>
    ): Set<String> {
        val normalizedKeys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
        if (normalizedKeys.isEmpty()) return emptySet()
        return try {
            database.withTransaction {
                val nowMs = System.currentTimeMillis()
                normalizedKeys.forEach { stableKey ->
                    database.syncMetadataDao().upsertMigrationMetadata(
                        MigrationMetadataEntity(
                            key = USER_CLEAR_SUPPRESSION_METADATA_KEY_PREFIX + stableKey,
                            value = DownloadRecoveryRoomStore.USER_CLEARED_STATE,
                            updatedAt = nowMs
                        )
                    )
                }
                normalizedKeys
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private suspend fun isLegacyQueueImportSuppressed(): Boolean {
        return database.syncMetadataDao()
            .getMigrationMetadata(DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY)
            ?.value == DownloadRecoveryRoomStore.USER_CLEARED_STATE
    }

    private fun countUserClearSuppressedPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase
    ): Int {
        return database.query(
            "SELECT COUNT(*) FROM $PAYLOAD_TABLE payload " +
                "WHERE EXISTS (SELECT 1 FROM migration_metadata marker " +
                "WHERE marker.key = ? || payload.stable_key AND marker.value = ?)",
            arrayOf(
                USER_CLEAR_SUPPRESSION_METADATA_KEY_PREFIX,
                DownloadRecoveryRoomStore.USER_CLEARED_STATE
            )
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun quarantineUnresolvedPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase
    ): Int {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $QUARANTINE_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                stable_key TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                reason TEXT NOT NULL,
                quarantined_at_ms INTEGER NOT NULL,
                UNIQUE(stable_key, payload_json)
            )
            """.trimIndent()
        )
        val candidates = countUnresolvedPayloadRows(database)
        if (candidates == 0) return 0
        database.beginTransaction()
        try {
            database.execSQL(
                """
                INSERT OR IGNORE INTO $QUARANTINE_TABLE (
                    stable_key,
                    payload_json,
                    reason,
                    quarantined_at_ms
                )
                SELECT
                    stable_key,
                    payload_json,
                    'UNTRUSTWORTHY_STABLE_IDENTITY',
                    ?
                FROM $PAYLOAD_TABLE
                WHERE stable_key LIKE 'legacy:%'
                   OR stable_key LIKE 'legacy-snapshot:%'
                """.trimIndent(),
                arrayOf(System.currentTimeMillis())
            )
            database.execSQL(
                """
                DELETE FROM $PAYLOAD_TABLE
                WHERE (
                    stable_key LIKE 'legacy:%'
                    OR stable_key LIKE 'legacy-snapshot:%'
                )
                AND EXISTS (
                    SELECT 1
                    FROM $QUARANTINE_TABLE quarantined
                    WHERE quarantined.stable_key = $PAYLOAD_TABLE.stable_key
                      AND quarantined.payload_json = $PAYLOAD_TABLE.payload_json
                )
                """.trimIndent()
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return candidates - countUnresolvedPayloadRows(database)
    }

    private fun countUnresolvedPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase
    ): Int {
        return database.query(
            "SELECT COUNT(*) FROM $PAYLOAD_TABLE " +
                "WHERE stable_key LIKE 'legacy:%' " +
                "OR stable_key LIKE 'legacy-snapshot:%'"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun quarantineTerminalPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        rows: List<PayloadRow>,
        results: List<LegacyDownloadUpgradeRowResult>
    ): Set<String> {
        val rowsByStableKey = rows.associateBy(PayloadRow::stableKey)
        val terminalResults = results.filter { result ->
            result.status == LegacyDownloadUpgradeRowStatus.INVALID_PAYLOAD ||
                result.status == LegacyDownloadUpgradeRowStatus.CONFLICT
        }
        if (terminalResults.isEmpty()) return emptySet()
        val quarantined = linkedSetOf<String>()
        database.beginTransaction()
        try {
            terminalResults.forEach { result ->
                val row = rowsByStableKey[result.stableKey] ?: return@forEach
                database.execSQL(
                    "INSERT OR IGNORE INTO $QUARANTINE_TABLE " +
                        "(stable_key, payload_json, reason, quarantined_at_ms) " +
                        "VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(
                        row.stableKey,
                        row.payloadJson,
                        result.status.name,
                        System.currentTimeMillis()
                    )
                )
                val stored = database.query(
                    "SELECT 1 FROM $QUARANTINE_TABLE " +
                        "WHERE stable_key = ? AND payload_json = ? LIMIT 1",
                    arrayOf(row.stableKey, row.payloadJson)
                ).use { cursor -> cursor.moveToFirst() }
                if (stored) quarantined += row.stableKey
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return quarantined
    }

    private fun deleteSettledPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        stableKeys: List<String>
    ) {
        if (stableKeys.isEmpty()) return
        val placeholders = List(stableKeys.size) { "?" }.joinToString(",")
        database.execSQL(
            "DELETE FROM $PAYLOAD_TABLE WHERE stable_key IN ($placeholders)",
            stableKeys.toTypedArray()
        )
    }

    private fun readQuarantinedPayloadRows(
        database: androidx.sqlite.db.SupportSQLiteDatabase
    ): List<PayloadRow> {
        return database.query(
            "SELECT stable_key, payload_json FROM $QUARANTINE_TABLE ORDER BY id ASC"
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

    private fun createPayloadTable(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS $PAYLOAD_TABLE (" +
                "stable_key TEXT NOT NULL PRIMARY KEY, " +
                "payload_json TEXT NOT NULL)"
        )
    }

    private fun tableExists(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String = PAYLOAD_TABLE
    ): Boolean {
        return database.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName)
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
        internal const val QUARANTINE_TABLE = "legacy_download_upgrade_quarantine"
        private const val USER_CLEAR_SUPPRESSION_METADATA_KEY_PREFIX =
            "legacy_download_upgrade_queue_suppressed:"
        private const val ROW_BATCH_SIZE = 64
        private const val ROW_PROCESS_PARALLELISM = 8
        private const val PROGRESS_UPDATE_INTERVAL = 16
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

internal suspend fun fingerprintFirstMatchingManagedCover(
    managedEntries: Sequence<ManagedDownloadStorage.StoredEntry>,
    expectedHash: String,
    fingerprint: suspend (
        ManagedDownloadStorage.StoredEntry
    ) -> ManagedDownloadCoverAssetStore.MaterializedCover?
): ManagedDownloadCoverAssetStore.MaterializedCover? {
    managedEntries.forEach { managedEntry ->
        val result = fingerprint(managedEntry) ?: return@forEach
        if (result.assetHash.equals(expectedHash, ignoreCase = true)) {
            return result
        }
    }
    return null
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

    fun addReferenceAndDecodedName(value: String?) {
        addReference(value)
        ManagedDownloadStorage.normalizeManagedAudioFileName(value)
            ?.takeIf { fileName ->
                fileName.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase(Locale.ROOT) in audioExtensions
            }
            ?.let(::addName)
    }

    val topLevelReferences = listOf(
        payload.optString("mediaUri"),
        payload.optString("media_uri"),
        payload.optString("filePath"),
        payload.optString("file_path"),
        payload.optString("sourceMediaUri"),
        payload.optString("audioReference"),
        payload.optString("audio_reference")
    )
    topLevelReferences.forEach(::addReference)

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
    topLevelReferences.forEach(::addReferenceAndDecodedName)

    payload.optJSONArray("download_snapshot_entries")?.let { entries ->
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val entryReferences = listOf(
                entry.optString("reference"),
                entry.optString("media_uri"),
                entry.optString("mediaUri"),
                entry.optString("file_path"),
                entry.optString("filePath")
            )
            entryReferences.forEach(::addReference)
            addName(entry.optString("name"))
            addName(entry.optString("audio_name"))
            addName(entry.optString("audioName"))
            entryReferences.forEach(::addReferenceAndDecodedName)
        }
    }
    return LegacyAudioLookupHints(
        references = references.toList(),
        names = names.toList()
    )
}

internal fun legacyPayloadNeedsManagedRootSnapshot(payloadJson: String): Boolean {
    val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return true
    return legacyPayloadNeedsManagedRootSnapshot(payload)
}

private fun legacyPayloadNeedsManagedRootSnapshot(payload: JSONObject): Boolean {
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
