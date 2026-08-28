package moe.ouom.neriplayer.core.download.storage.migration

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.json.JSONObject

internal class ManagedDownloadMigrationCheckpointStore internal constructor(
    private val preferences: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    )

    fun readMinimumAudioCount(workId: String): Int {
        return runCatching {
            preferences.getInt(keyFor(workId), 0)
        }.getOrDefault(0).coerceAtLeast(0)
    }

    /**
     * returns the complete migration input that was committed before enqueue
     */
    fun readRequest(): ManagedMigrationRequest? {
        val raw = runCatching {
            preferences.getString(ACTIVE_REQUEST_KEY, null)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        return try {
            decodeRequest(JSONObject(raw))
        } catch (error: ManagedDownloadMigrationException) {
            throw error
        } catch (error: Exception) {
            throw ManagedDownloadMigrationException.transient(
                "迁移请求无法读取，等待恢复",
                error
            )
        }
    }

    // commit is intentional because the process may die before WorkManager enqueue
    @SuppressLint("UseKtx")
    fun recordRequest(request: ManagedMigrationRequest): ManagedMigrationRequest {
        val normalized = request.normalized()
        if (normalized.workId.isBlank()) {
            throw ManagedDownloadMigrationException.transient(
                "迁移请求缺少任务标识，等待重试"
            )
        }
        val committed = preferences.edit()
            .putString(ACTIVE_REQUEST_KEY, encodeRequest(normalized).toString())
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移请求"
            )
        }
        return normalized
    }

    /**
     * terminal failure must not be restarted forever, while its journal remains
     * available for an explicit retry from the settings screen
     */
    fun markRequestTerminal(workId: String): Boolean {
        val current = readRequest() ?: return false
        if (current.workId != workId) return false
        recordRequest(current.copy(autoResume = false))
        return true
    }

    @SuppressLint("UseKtx")
    fun clearRequest(workId: String? = null): Boolean {
        if (workId != null) {
            val current = readRequest()
            if (current != null && current.workId != workId) return true
        }
        return preferences.edit().remove(ACTIVE_REQUEST_KEY).commit()
    }

    /**
     * clears all migration credentials in one SharedPreferences commit after the
     * target has been verified and the catalog scan has been published
     */
    @SuppressLint("UseKtx")
    fun clearCompleted(workIds: Collection<String>): Boolean {
        val normalizedIds = workIds.map(String::trim).filter(String::isNotBlank).toSet()
        val currentRequest = readRequest()
        val currentJournal = readReplacementJournal()
        val editor = preferences.edit()
        normalizedIds.forEach { workId ->
            editor.remove(keyFor(workId))
            editor.remove(targetNamesKeyFor(workId))
            editor.remove(progressKeyFor(workId))
        }
        if (
            currentRequest == null ||
                currentRequest.workId in normalizedIds ||
                currentRequest.checkpointWorkId?.let(normalizedIds::contains) == true
        ) {
            editor.remove(ACTIVE_REQUEST_KEY)
        }
        // 只清理属于本次已验证迁移的替换事务，不能让并发或旧事务的
        // journal 在另一项迁移完成时被误删
        if (currentJournal == null || currentJournal.workId in normalizedIds) {
            editor.remove(ACTIVE_REPLACEMENT_JOURNAL_KEY)
        }
        return editor.commit()
    }

    // KTX edit discards commit's boolean result, which is required for retry decisions
    @SuppressLint("UseKtx")
    fun recordMinimumAudioCount(workId: String, minimumAudioCount: Int): Int {
        val persistedCount = readMinimumAudioCount(workId)
        val resolvedCount = maxOf(persistedCount, minimumAudioCount).coerceAtLeast(0)
        val committed = preferences.edit()
            .putInt(keyFor(workId), resolvedCount)
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移文件下界"
            )
        }
        return resolvedCount
    }

    fun readTargetNames(workId: String): Map<String, String> {
        val raw = runCatching {
            preferences.getString(targetNamesKeyFor(workId), null)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { sourceReference ->
                    root.optString(sourceReference)
                        .trim()
                        .takeIf(String::isNotBlank)
                        ?.let { targetName -> put(sourceReference, targetName) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    @SuppressLint("UseKtx")
    fun recordTargetNames(workId: String, targetNames: Map<String, String>): Map<String, String> {
        val payload = JSONObject().apply {
            targetNames.toSortedMap().forEach { (sourceReference, targetName) ->
                put(sourceReference, targetName)
            }
        }.toString()
        val committed = preferences.edit()
            .putString(targetNamesKeyFor(workId), payload)
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移目标计划"
            )
        }
        return targetNames
    }

    /**
     * reads the last durable migration progress snapshot. A missing or malformed
     * optional progress record must not invalidate the file migration journal
     */
    fun readProgress(workId: String): ManagedDownloadStorage.MigrationProgress? {
        val raw = runCatching {
            preferences.getString(progressKeyFor(workId), null)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            decodeProgress(JSONObject(raw))
        }.getOrNull()
    }

    /**
     * persists a complete progress snapshot so a restarted worker can explain
     * its current phase before it touches either storage root
     */
    @SuppressLint("UseKtx")
    fun recordProgress(
        workId: String,
        progress: ManagedDownloadStorage.MigrationProgress
    ): ManagedDownloadStorage.MigrationProgress {
        val normalized = normalizeProgress(progress)
        val durableProgress = mergeMigrationProgressFloor(
            floor = readProgress(workId),
            current = normalized
        )
        val committed = preferences.edit()
            .putString(progressKeyFor(workId), encodeProgress(durableProgress).toString())
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移进度"
            )
        }
        return durableProgress
    }

    /**
     * Returns the active replacement journal, if one was durably written.
     * Unknown fields are ignored so journals written by a newer build remain
     * readable after a rollback.
     */
    fun readReplacementJournal(): ManagedMigrationReplacementJournal? {
        val raw = runCatching {
            preferences.getString(ACTIVE_REPLACEMENT_JOURNAL_KEY, null)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        return try {
            decodeReplacementJournal(JSONObject(raw))
        } catch (error: ManagedDownloadMigrationException) {
            throw error
        } catch (error: Exception) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务无法读取，等待重试",
                error
            )
        }
    }

    // commit is intentional because a killed worker must leave a complete plan
    @SuppressLint("UseKtx")
    fun recordReplacementJournal(
        journal: ManagedMigrationReplacementJournal
    ): ManagedMigrationReplacementJournal {
        val committed = preferences.edit()
            .putString(ACTIVE_REPLACEMENT_JOURNAL_KEY, encodeReplacementJournal(journal).toString())
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移替换事务"
            )
        }
        return journal
    }

    @SuppressLint("UseKtx")
    fun clearReplacementJournal(): Boolean {
        return preferences.edit().remove(ACTIVE_REPLACEMENT_JOURNAL_KEY).commit()
    }

    @SuppressLint("UseKtx")
    fun clear(workId: String): Boolean {
        return preferences.edit()
            .remove(keyFor(workId))
            .remove(targetNamesKeyFor(workId))
            .remove(progressKeyFor(workId))
            .commit()
    }

    private fun keyFor(workId: String): String = "$KEY_PREFIX$workId"

    private fun targetNamesKeyFor(workId: String): String = "$TARGET_NAMES_KEY_PREFIX$workId"

    private fun progressKeyFor(workId: String): String = "$PROGRESS_KEY_PREFIX$workId"

    private fun encodeRequest(request: ManagedMigrationRequest): JSONObject {
        return JSONObject().apply {
            put("version", CURRENT_MIGRATION_REQUEST_VERSION)
            put("workId", request.workId)
            put("fromDirectoryUri", request.fromDirectoryUri)
            put("toDirectoryUri", request.toDirectoryUri)
            put("targetLabel", request.targetLabel)
            put("releasePreviousPermission", request.releasePreviousPermission)
            put("minimumSourceEntryCount", request.minimumSourceEntryCount)
            put("checkpointWorkId", request.checkpointWorkId)
            put("autoResume", request.autoResume)
        }
    }

    private fun decodeRequest(root: JSONObject): ManagedMigrationRequest {
        val version = root.optInt("version", CURRENT_MIGRATION_REQUEST_VERSION)
        if (version != CURRENT_MIGRATION_REQUEST_VERSION) {
            throw ManagedDownloadMigrationException.transient(
                "迁移请求版本不受支持: $version"
            )
        }
        val workId = root.optString("workId").trim()
        if (workId.isBlank()) {
            throw ManagedDownloadMigrationException.transient(
                "迁移请求缺少任务标识"
            )
        }
        return ManagedMigrationRequest(
            workId = workId,
            fromDirectoryUri = root.optNullableString("fromDirectoryUri"),
            toDirectoryUri = root.optNullableString("toDirectoryUri"),
            targetLabel = root.optString("targetLabel").trim(),
            releasePreviousPermission = root.optBoolean(
                "releasePreviousPermission",
                false
            ),
            minimumSourceEntryCount = root.optInt(
                "minimumSourceEntryCount",
                0
            ).coerceAtLeast(0),
            checkpointWorkId = root.optNullableString("checkpointWorkId"),
            autoResume = root.optBoolean("autoResume", true)
        ).normalized()
    }

    private fun encodeProgress(
        progress: ManagedDownloadStorage.MigrationProgress
    ): JSONObject {
        return JSONObject().apply {
            put("version", CURRENT_MIGRATION_PROGRESS_VERSION)
            put("stage", progress.stage.name)
            put("totalFiles", progress.totalFiles)
            put("processedFiles", progress.processedFiles)
            put("copiedFiles", progress.copiedFiles)
            put("copiedBytes", progress.copiedBytes)
            put("totalBytes", progress.totalBytes)
            put("metadataFilesProcessed", progress.metadataFilesProcessed)
            put("metadataFilesTotal", progress.metadataFilesTotal)
            put("cleanupFilesProcessed", progress.cleanupFilesProcessed)
            put("cleanupFilesTotal", progress.cleanupFilesTotal)
            put("verificationFilesProcessed", progress.verificationFilesProcessed)
            put("verificationFilesTotal", progress.verificationFilesTotal)
            put("verifiedBytes", progress.verifiedBytes)
            put("verificationBytesTotal", progress.verificationBytesTotal)
            progress.currentFileName?.let { currentFileName ->
                put("currentFileName", currentFileName)
            }
        }
    }

    private fun decodeProgress(
        root: JSONObject
    ): ManagedDownloadStorage.MigrationProgress? {
        if (
            root.optInt("version", CURRENT_MIGRATION_PROGRESS_VERSION) !=
                CURRENT_MIGRATION_PROGRESS_VERSION
        ) {
            return null
        }
        val stage = ManagedDownloadStorage.MigrationStage.entries.firstOrNull { candidate ->
            candidate.name == root.optString("stage").trim()
        } ?: return null
        val totalFiles = root.optInt("totalFiles", 0).coerceAtLeast(0)
        val processedFiles = root.optInt("processedFiles", 0)
            .coerceAtLeast(0)
            .coerceAtMost(totalFiles)
        val metadataFilesTotal = root.optInt("metadataFilesTotal", 0).coerceAtLeast(0)
        val cleanupFilesTotal = root.optInt("cleanupFilesTotal", 0).coerceAtLeast(0)
        val verificationFilesTotal = root.optInt("verificationFilesTotal", 0)
            .coerceAtLeast(0)
        return ManagedDownloadStorage.MigrationProgress(
            stage = stage,
            totalFiles = totalFiles,
            processedFiles = processedFiles,
            copiedFiles = root.optInt("copiedFiles", 0)
                .coerceAtLeast(0)
                .coerceAtMost(totalFiles),
            copiedBytes = root.optLong("copiedBytes", 0L).coerceAtLeast(0L),
            totalBytes = root.optLong("totalBytes", 0L).coerceAtLeast(0L),
            metadataFilesProcessed = root.optInt("metadataFilesProcessed", 0)
                .coerceAtLeast(0)
                .coerceAtMost(metadataFilesTotal),
            metadataFilesTotal = metadataFilesTotal,
            cleanupFilesProcessed = root.optInt("cleanupFilesProcessed", 0)
                .coerceAtLeast(0)
                .coerceAtMost(cleanupFilesTotal),
            cleanupFilesTotal = cleanupFilesTotal,
            currentFileName = root.optString("currentFileName")
                .trim()
                .takeIf(String::isNotBlank),
            verificationFilesProcessed = root.optInt("verificationFilesProcessed", 0)
                .coerceAtLeast(0)
                .coerceAtMost(verificationFilesTotal),
            verificationFilesTotal = verificationFilesTotal,
            verifiedBytes = root.optLong("verifiedBytes", 0L).coerceAtLeast(0L),
            verificationBytesTotal = root.optLong("verificationBytesTotal", 0L)
                .coerceAtLeast(0L)
        )
    }

    private fun normalizeProgress(
        progress: ManagedDownloadStorage.MigrationProgress
    ): ManagedDownloadStorage.MigrationProgress {
        val totalFiles = progress.totalFiles.coerceAtLeast(0)
        val metadataFilesTotal = progress.metadataFilesTotal.coerceAtLeast(0)
        val cleanupFilesTotal = progress.cleanupFilesTotal.coerceAtLeast(0)
        val verificationFilesTotal = progress.verificationFilesTotal.coerceAtLeast(0)
        return progress.copy(
            totalFiles = totalFiles,
            processedFiles = progress.processedFiles.coerceAtLeast(0).coerceAtMost(totalFiles),
            copiedFiles = progress.copiedFiles.coerceAtLeast(0).coerceAtMost(totalFiles),
            copiedBytes = progress.copiedBytes.coerceAtLeast(0L),
            totalBytes = progress.totalBytes.coerceAtLeast(0L),
            metadataFilesProcessed = progress.metadataFilesProcessed
                .coerceAtLeast(0)
                .coerceAtMost(metadataFilesTotal),
            metadataFilesTotal = metadataFilesTotal,
            cleanupFilesProcessed = progress.cleanupFilesProcessed
                .coerceAtLeast(0)
                .coerceAtMost(cleanupFilesTotal),
            cleanupFilesTotal = cleanupFilesTotal,
            currentFileName = progress.currentFileName
                ?.trim()
                ?.takeIf(String::isNotBlank),
            verificationFilesProcessed = progress.verificationFilesProcessed
                .coerceAtLeast(0)
                .coerceAtMost(verificationFilesTotal),
            verificationFilesTotal = verificationFilesTotal,
            verifiedBytes = progress.verifiedBytes.coerceAtLeast(0L),
            verificationBytesTotal = progress.verificationBytesTotal.coerceAtLeast(0L)
        )
    }

    private fun encodeReplacementJournal(
        journal: ManagedMigrationReplacementJournal
    ): JSONObject {
        return JSONObject().apply {
            put("version", journal.version)
            put("workId", journal.workId)
            put("fromDirectoryUri", journal.fromDirectoryUri)
            put("toDirectoryUri", journal.toDirectoryUri)
            put("backupNamespace", journal.backupNamespace)
            put("phase", journal.phase.name)
            put("targetNames", JSONObject().apply {
                journal.targetNamesByReference.toSortedMap().forEach { (reference, name) ->
                    put(reference, name)
                }
            })
            put("replacements", org.json.JSONArray().apply {
                journal.replacements.sortedWith(
                    compareBy<ManagedMigrationReplacementPlan>(
                        { it.sourceReference },
                        { it.subdirectory.orEmpty() },
                        { it.targetName }
                    )
                ).forEach { replacement ->
                    put(encodeReplacementPlan(replacement))
                }
            })
        }
    }

    private fun encodeReplacementPlan(
        replacement: ManagedMigrationReplacementPlan
    ): JSONObject {
        return JSONObject().apply {
            put("sourceReference", replacement.sourceReference)
            put("groupIdentity", replacement.groupIdentity)
            put("subdirectory", replacement.subdirectory)
            put("targetName", replacement.targetName)
            put("backupName", replacement.backupName)
            put("targetEntry", encodeStoredEntry(replacement.targetEntry))
        }
    }

    private fun encodeStoredEntry(
        entry: moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
    ): JSONObject {
        return JSONObject().apply {
            put("name", entry.name)
            put("reference", entry.reference)
            put("mediaUri", entry.mediaUri)
            put("localFilePath", entry.localFilePath)
            put("sizeBytes", entry.sizeBytes)
            put("lastModifiedMs", entry.lastModifiedMs)
            put("isDirectory", entry.isDirectory)
        }
    }

    private fun decodeReplacementJournal(root: JSONObject): ManagedMigrationReplacementJournal {
        val workId = root.optString("workId").trim().takeIf(String::isNotBlank)
            ?: invalidJournal("迁移替换事务缺少 workId")
        val version = root.optInt(
            "version",
            CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION
        )
        if (version != CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION) {
            invalidJournal("迁移替换事务版本不受支持: $version")
        }
        val backupNamespace = root.optString("backupNamespace", "migration")
            .trim().takeIf(String::isNotBlank) ?: "migration"
        val phaseName = root.optString("phase").trim()
        val phase = ManagedMigrationReplacementJournalPhase.entries
            .firstOrNull { candidate -> candidate.name == phaseName }
            ?: invalidJournal("迁移替换事务阶段无法识别: $phaseName")
        val array = root.optJSONArray("replacements")
            ?: invalidJournal("迁移替换事务缺少 replacement 计划")
        if (array.length() == 0) {
            invalidJournal("迁移替换事务没有 replacement 计划")
        }
        val replacements = buildList {
            for (index in 0 until array.length()) {
                decodeReplacementPlan(array.optJSONObject(index))
                    ?.let(::add)
                    ?: invalidJournal("迁移替换事务包含无效 replacement: index=$index")
            }
        }
        val targetNames = decodeTargetNames(root.optJSONObject("targetNames"))
        return ManagedMigrationReplacementJournal(
            version = version,
            workId = workId,
            fromDirectoryUri = root.optNullableString("fromDirectoryUri"),
            toDirectoryUri = root.optNullableString("toDirectoryUri"),
            backupNamespace = backupNamespace,
            phase = phase,
            replacements = replacements,
            targetNamesByReference = targetNames
        )
    }

    private fun decodeTargetNames(value: JSONObject?): Map<String, String> {
        value ?: return emptyMap()
        return buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val reference = keys.next().trim()
                val targetName = value.optString(reference).trim()
                if (reference.isBlank() || !isSafeMigrationPlanName(targetName)) {
                    invalidJournal("迁移替换事务包含无效目标名称")
                }
                put(reference, targetName)
            }
        }
    }

    private fun invalidJournal(message: String): Nothing {
        throw ManagedDownloadMigrationException.transient(message)
    }

    private fun decodeReplacementPlan(value: JSONObject?): ManagedMigrationReplacementPlan? {
        value ?: return null
        val sourceReference = value.optString("sourceReference").trim()
        val groupIdentity = value.optString("groupIdentity").trim()
        val targetName = value.optString("targetName").trim()
        val backupName = value.optString("backupName").trim()
        val targetEntry = decodeStoredEntry(value.optJSONObject("targetEntry"))
        if (
            sourceReference.isBlank() || groupIdentity.isBlank() || targetName.isBlank() ||
            backupName.isBlank() || targetEntry == null ||
            !isSafeMigrationPlanName(targetName) ||
            !isSafeMigrationPlanName(backupName) ||
            targetEntry.isDirectory ||
            targetEntry.name != targetName
        ) {
            return null
        }
        return ManagedMigrationReplacementPlan(
            sourceReference = sourceReference,
            groupIdentity = groupIdentity,
            subdirectory = value.optNullableString("subdirectory"),
            targetName = targetName,
            targetEntry = targetEntry,
            backupName = backupName
        )
    }

    private fun decodeStoredEntry(value: JSONObject?):
        moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry? {
        value ?: return null
        val name = value.optString("name").trim()
        val reference = value.optString("reference").trim()
        val mediaUri = value.optString("mediaUri").trim()
        if (name.isBlank() || reference.isBlank() || mediaUri.isBlank()) return null
        return moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = value.optNullableString("localFilePath"),
            sizeBytes = value.optLong("sizeBytes", 0L),
            lastModifiedMs = value.optLong("lastModifiedMs", 0L),
            isDirectory = value.optBoolean("isDirectory", false)
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf(String::isNotBlank)
    }

    companion object {
        internal const val PREFERENCES_NAME = "managed_download_migration_checkpoint"
        internal const val KEY_PREFIX = "minimum_audio_count:"
        internal const val TARGET_NAMES_KEY_PREFIX = "target_names:"
        internal const val PROGRESS_KEY_PREFIX = "progress:"
        internal const val ACTIVE_REQUEST_KEY = "request:active"
        internal const val ACTIVE_REPLACEMENT_JOURNAL_KEY = "replacement_journal:active"
        internal const val CURRENT_MIGRATION_REQUEST_VERSION = 1
        internal const val CURRENT_MIGRATION_PROGRESS_VERSION = 1
    }
}
