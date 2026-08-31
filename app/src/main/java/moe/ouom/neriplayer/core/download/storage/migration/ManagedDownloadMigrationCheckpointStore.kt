package moe.ouom.neriplayer.core.download.storage.migration

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

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
     * 返回入队前已经持久化的完整迁移输入
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

    // 这里必须同步提交，因为进程可能在 WorkManager 入队前被终止
    @SuppressLint("UseKtx")
    fun recordRequest(request: ManagedMigrationRequest): ManagedMigrationRequest {
        return synchronized(requestMutationLock) {
            writeRequestLocked(request)
        }
    }

    /**
     * 只有调用方读到的请求仍然是当前请求时才写回
     * 避免被替换的 WorkManager Worker 用旧编号覆盖新的持久请求
     */
    @SuppressLint("UseKtx")
    fun recordRequestIfCurrent(
        expectedWorkId: String?,
        request: ManagedMigrationRequest,
        expectedAutoResume: Boolean? = null,
        expectedRequest: ManagedMigrationRequest? = null
    ): Boolean {
        return synchronized(requestMutationLock) {
            val current = readRequest()
            val expected = expectedWorkId?.trim()?.takeIf(String::isNotBlank)
            if (
                (expected == null && current != null) ||
                (expected != null && !migrationWorkIdsEqual(current?.workId, expected))
            ) {
                return@synchronized false
            }
            if (expectedAutoResume != null && current?.autoResume != expectedAutoResume) {
                return@synchronized false
            }
            if (expectedRequest != null && !migrationRequestsEqual(current, expectedRequest)) {
                return@synchronized false
            }
            writeRequestLocked(request)
            true
        }
    }

    /**
     * 目录方向改变时把旧活动凭据归档，再原子切换到新的请求
     * 旧 work 的按编号检查点不改动，仍可用于故障取证和手动恢复
     */
    @SuppressLint("UseKtx")
    fun replaceActiveRequestForDifferentRoots(
        request: ManagedMigrationRequest
    ): ManagedMigrationRequest {
        return synchronized(requestMutationLock) {
            val normalized = request.normalized()
            val currentRaw = runCatching {
                preferences.getString(ACTIVE_REQUEST_KEY, null)
            }.getOrNull()?.takeIf(String::isNotBlank)
            val current = currentRaw?.let { raw ->
                runCatching { decodeRequest(JSONObject(raw)) }.getOrNull()
            }
            val journalRaw = runCatching {
                preferences.getString(ACTIVE_REPLACEMENT_JOURNAL_KEY, null)
            }.getOrNull()?.takeIf(String::isNotBlank)
            val journal = journalRaw?.let { raw ->
                runCatching { decodeReplacementJournal(JSONObject(raw)) }.getOrNull()
            }
            val rootsChanged = when {
                current == null && currentRaw != null -> true
                current != null && migrationRootsDiffer(
                    current.fromDirectoryUri,
                    current.toDirectoryUri,
                    normalized.fromDirectoryUri,
                    normalized.toDirectoryUri
                ) -> true
                current == null && journalRaw != null -> true
                journalRaw != null && journal == null -> true
                journal != null && migrationRootsDiffer(
                    journal.fromDirectoryUri,
                    journal.toDirectoryUri,
                    normalized.fromDirectoryUri,
                    normalized.toDirectoryUri
                ) -> true
                else -> false
            }
            if (!rootsChanged) {
                return@synchronized writeRequestLocked(normalized)
            }

            val archiveId = UUID.randomUUID().toString()
            val editor = preferences.edit()
            val archivedRequest = currentRaw ?: current?.let(::encodeRequest)?.toString()
            archivedRequest?.let { raw ->
                editor.putString(
                    archivedRequestKeyFor(archiveId),
                    raw
                )
            }
            editor
                .putString(
                    ACTIVE_REQUEST_KEY,
                    encodeRequest(normalized).toString()
                )
                .remove(ACTIVE_REPLACEMENT_JOURNAL_KEY)
            journalRaw?.let { raw ->
                editor.putString(archivedJournalKeyFor(archiveId), raw)
            }
            if (!editor.commit()) {
                throw ManagedDownloadMigrationException.transient(
                    "无法原子替换迁移请求，等待恢复"
                )
            }
            normalized
        }
    }

    /**
     * 终态失败不能无限自动重启，但要保留日志供设置页明确重试
     */
    fun markRequestTerminal(workId: String): Boolean {
        return synchronized(requestMutationLock) {
            val current = readRequest() ?: return@synchronized false
            if (!migrationWorkIdsEqual(current.workId, workId)) return@synchronized false
            writeRequestLocked(current.copy(autoResume = false))
            true
        }
    }

    /** 让仍处于可恢复状态的迁移请求可以被启动流程继续恢复 */
    fun markRequestRetryable(
        workId: String,
        retryAttemptOffset: Int? = null
    ): Boolean {
        return synchronized(requestMutationLock) {
            val current = readRequest() ?: return@synchronized false
            if (!migrationWorkIdsEqual(current.workId, workId)) return@synchronized false
            if (!current.autoResume) return@synchronized false
            val nextRetryAttemptOffset = maxOf(
                current.retryAttemptOffset,
                retryAttemptOffset?.coerceAtLeast(0) ?: 0
            )
            writeRequestLocked(
                current.copy(
                    autoResume = true,
                    retryAttemptOffset = nextRetryAttemptOffset
                )
            )
            true
        }
    }

    @SuppressLint("UseKtx")
    fun clearRequest(workId: String? = null): Boolean {
        return synchronized(requestMutationLock) {
            if (workId != null) {
                val current = readRequest()
                if (
                    current != null &&
                        !migrationWorkIdsEqual(current.workId, workId)
                ) {
                    return@synchronized true
                }
            }
            preferences.edit().remove(ACTIVE_REQUEST_KEY).commit()
        }
    }

    /**
     * 目标校验完成且 catalog 扫描发布后，用一次 SharedPreferences 提交清除迁移凭据
     */
    @SuppressLint("UseKtx")
    fun clearCompleted(workIds: Collection<String>): Boolean {
        return synchronized(requestMutationLock) {
            clearCompletedLocked(workIds)
        }
    }

    /**
     * 只有调用方仍拥有活动请求时才清除迁移凭据
     */
    @SuppressLint("UseKtx")
    fun clearCompletedIfCurrent(
        ownerWorkId: String,
        workIds: Collection<String>
    ): Boolean? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            clearCompletedLocked(workIds)
        }
    }

    /**
     * 在完成所有权仍被持有时执行最后的副作用
     *
     * 所有权检查、释放旧目录授权和清除已完成检查点之间不能插入替代请求
     */
    @SuppressLint("UseKtx")
    fun clearCompletedAndRunIfCurrent(
        ownerWorkId: String,
        workIds: Collection<String>,
        beforeClear: () -> Unit
    ): Boolean? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            beforeClear()
            clearCompletedLocked(workIds)
        }
    }

    @SuppressLint("UseKtx")
    private fun clearCompletedLocked(workIds: Collection<String>): Boolean {
        synchronized(copyReceiptMutationLock) {
            val normalizedIds = workIds.map(String::trim).filter(String::isNotBlank).toSet()
            val currentRequest = readRequest()
            val currentJournal = readReplacementJournal()
            val editor = preferences.edit()
            normalizedIds.forEach { workId ->
                editor.remove(keyFor(workId))
                editor.remove(targetNamesKeyFor(workId))
                editor.remove(progressKeyFor(workId))
                copyReceiptKeysFor(workId).forEach(editor::remove)
                editor.remove(copyReceiptIndexKeyFor(workId))
            }
            // 只有拥有活动请求的 Worker 可以清除它
            // checkpointWorkId 仍保留给旧进度和凭据清理使用，但不能当作所有权令牌
            if (
                currentRequest == null ||
                    containsEquivalentWorkId(currentRequest.workId, normalizedIds)
            ) {
                editor.remove(ACTIVE_REQUEST_KEY)
            }
            // 只清理属于本次已验证迁移的替换事务，不能让并发或旧事务的
            // journal 在另一项迁移完成时被误删
            if (
                currentJournal == null ||
                    containsEquivalentWorkId(currentJournal.workId, normalizedIds)
            ) {
                editor.remove(ACTIVE_REPLACEMENT_JOURNAL_KEY)
            }
            return editor.commit()
        }
    }

    fun isRequestCurrent(workId: String): Boolean {
        val normalizedWorkId = workId.trim().takeIf(String::isNotBlank) ?: return false
        return synchronized(requestMutationLock) {
            isRequestCurrentLocked(normalizedWorkId)
        }
    }

    /**
     * 只有当前 Worker 仍拥有请求时才持久化源文件数量
     */
    @SuppressLint("UseKtx")
    fun recordMinimumAudioCountIfCurrent(
        ownerWorkId: String,
        workId: String,
        minimumAudioCount: Int
    ): Int? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            recordMinimumAudioCountLocked(workId, minimumAudioCount)
        }
    }

    // KTX 的 edit 会丢掉 commit 返回值，而重试判断需要这个结果
    @SuppressLint("UseKtx")
    fun recordMinimumAudioCount(workId: String, minimumAudioCount: Int): Int {
        return synchronized(requestMutationLock) {
            recordMinimumAudioCountLocked(workId, minimumAudioCount)
        }
    }

    @SuppressLint("UseKtx")
    private fun recordMinimumAudioCountLocked(
        workId: String,
        minimumAudioCount: Int
    ): Int {
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
        return synchronized(requestMutationLock) {
            recordTargetNamesLocked(workId, targetNames)
        }
    }

    @SuppressLint("UseKtx")
    fun recordTargetNamesIfCurrent(
        ownerWorkId: String,
        workId: String,
        targetNames: Map<String, String>
    ): Map<String, String>? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            recordTargetNamesLocked(workId, targetNames)
        }
    }

    @SuppressLint("UseKtx")
    private fun recordTargetNamesLocked(
        workId: String,
        targetNames: Map<String, String>
    ): Map<String, String> {
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
     * 读取最后一份持久迁移进度快照，缺失或损坏的可选进度记录不能使文件迁移日志失效
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
     * 持久化完整进度快照，让重启后的 Worker 在访问任一存储根前就能恢复当前阶段
     */
    @SuppressLint("UseKtx")
    fun recordProgress(
        workId: String,
        progress: ManagedDownloadStorage.MigrationProgress
    ): ManagedDownloadStorage.MigrationProgress {
        return synchronized(requestMutationLock) {
            recordProgressLocked(workId, progress)
        }
    }

    @SuppressLint("UseKtx")
    fun recordProgressIfCurrent(
        ownerWorkId: String,
        workId: String,
        progress: ManagedDownloadStorage.MigrationProgress
    ): ManagedDownloadStorage.MigrationProgress? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            recordProgressLocked(workId, progress)
        }
    }

    @SuppressLint("UseKtx")
    private fun recordProgressLocked(
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
     * 返回进程重启后仍然保留的复制凭据
     */
    fun readCopyReceipts(workId: String): List<ManagedMigrationCopyReceipt> {
        val normalizedWorkId = workId.trim()
        if (normalizedWorkId.isBlank()) return emptyList()
        val prefix = copyReceiptKeyPrefixFor(normalizedWorkId)
        return runCatching {
            val indexedSuffixes = readIndexedCopyReceiptSuffixes(normalizedWorkId)
            val indexedReceipts = indexedSuffixes?.mapNotNull { suffix ->
                decodeCopyReceiptAtKey(prefix + suffix)
            }
            val receipts = if (
                indexedSuffixes != null &&
                    indexedReceipts?.size == indexedSuffixes.size
            ) {
                // 有效的空索引本身就是权威结果，这里再扫描所有偏好会让每次完成迁移都付出全局查找成本
                indexedReceipts.orEmpty()
            } else {
                scanCopyReceiptKeys(normalizedWorkId)
                    .mapNotNull(::decodeCopyReceiptAtKey)
            }
            receipts
                .distinctBy(ManagedMigrationCopyReceipt::sourceReference)
                .sortedWith(
                    compareBy<ManagedMigrationCopyReceipt>(
                        { it.sourceSubdirectory.orEmpty() },
                        { it.sourceName },
                        { it.sourceReference }
                    )
                )
                .toList()
        }.getOrDefault(emptyList())
    }

    fun readCopyReceipt(workId: String, sourceReference: String): ManagedMigrationCopyReceipt? {
        val normalizedWorkId = workId.trim()
        val normalizedReference = sourceReference.trim()
        if (normalizedWorkId.isBlank() || normalizedReference.isBlank()) return null
        val raw = runCatching {
            preferences.getString(
                copyReceiptKeyFor(normalizedWorkId, normalizedReference),
                null
            )
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        return runCatching { decodeCopyReceipt(JSONObject(raw)) }.getOrNull()
    }

    @SuppressLint("UseKtx")
    fun recordCopyReceipt(
        workId: String,
        receipt: ManagedMigrationCopyReceipt
    ): ManagedMigrationCopyReceipt {
        recordCopyReceipts(workId, listOf(receipt))
        return receipt
    }

    /**
     * 用一次索引改写提交有界批次的复制凭据
     * 凭据载荷仍可逐项读取，批次中断时最多丢失当前批次，不会影响已提交条目
     */
    @SuppressLint("UseKtx")
    fun recordCopyReceipts(
        workId: String,
        receipts: Collection<ManagedMigrationCopyReceipt>
    ): Int {
        return synchronized(copyReceiptMutationLock) {
            recordCopyReceiptsLocked(workId, receipts)
        }
    }

    @SuppressLint("UseKtx")
    fun recordCopyReceiptsIfCurrent(
        ownerWorkId: String,
        workId: String,
        receipts: Collection<ManagedMigrationCopyReceipt>
    ): Int? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            synchronized(copyReceiptMutationLock) {
                recordCopyReceiptsLocked(workId, receipts)
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun recordCopyReceiptsLocked(
        workId: String,
        receipts: Collection<ManagedMigrationCopyReceipt>
    ): Int {
        val normalizedWorkId = workId.trim()
        if (normalizedWorkId.isBlank()) {
            throw ManagedDownloadMigrationException.transient(
                "迁移复制凭据缺少任务标识，等待重试"
            )
        }
        val normalizedReceipts = linkedMapOf<String, ManagedMigrationCopyReceipt>()
        receipts.forEach { receipt ->
            validateCopyReceipt(receipt)
            val reference = receipt.sourceReference.trim()
            val previous = normalizedReceipts[reference]
            if (previous != null && previous != receipt) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移复制凭据批次存在不一致: $reference"
                )
            }
            normalizedReceipts[reference] = receipt
        }
        if (normalizedReceipts.isEmpty()) return 0
        val existingSuffixes = reliableCopyReceiptSuffixes(normalizedWorkId)
        val updatedSuffixes = existingSuffixes.toMutableSet()
        val editor = preferences.edit()
        normalizedReceipts.forEach { (sourceReference, receipt) ->
            editor.putString(
                copyReceiptKeyFor(normalizedWorkId, sourceReference),
                encodeCopyReceipt(receipt).toString()
            )
            updatedSuffixes += copyReceiptKeySuffixFor(sourceReference)
        }
        val committed = editor
            .putString(
                copyReceiptIndexKeyFor(normalizedWorkId),
                encodeCopyReceiptIndex(updatedSuffixes)
            )
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移复制凭据"
            )
        }
        return normalizedReceipts.size
    }

    @SuppressLint("UseKtx")
    fun clearCopyReceipt(workId: String, sourceReference: String): Boolean {
        return synchronized(copyReceiptMutationLock) {
            clearCopyReceiptLocked(workId, sourceReference)
        }
    }

    @SuppressLint("UseKtx")
    fun clearCopyReceiptIfCurrent(
        ownerWorkId: String,
        workId: String,
        sourceReference: String
    ): Boolean? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            synchronized(copyReceiptMutationLock) {
                clearCopyReceiptLocked(workId, sourceReference)
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun clearCopyReceiptLocked(workId: String, sourceReference: String): Boolean {
        val normalizedWorkId = workId.trim()
        val normalizedReference = sourceReference.trim()
        if (normalizedWorkId.isBlank() || normalizedReference.isBlank()) return true
        val existingSuffixes = reliableCopyReceiptSuffixes(normalizedWorkId)
        val updatedSuffixes = existingSuffixes - copyReceiptKeySuffixFor(normalizedReference)
        return preferences.edit()
            .remove(copyReceiptKeyFor(normalizedWorkId, normalizedReference))
            .putString(
                copyReceiptIndexKeyFor(normalizedWorkId),
                encodeCopyReceiptIndex(updatedSuffixes)
            )
            .commit()
    }

    /**
     * 返回已经持久化的活动替换日志
     * 忽略未知字段，让新版本写入的日志在回滚版本中仍可读取
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

    // 这里必须同步提交，被终止的 Worker 也要留下完整计划
    @SuppressLint("UseKtx")
    fun recordReplacementJournal(
        journal: ManagedMigrationReplacementJournal
    ): ManagedMigrationReplacementJournal {
        return synchronized(requestMutationLock) {
            recordReplacementJournalLocked(journal)
        }
    }

    @SuppressLint("UseKtx")
    fun recordReplacementJournalIfCurrent(
        ownerWorkId: String,
        journal: ManagedMigrationReplacementJournal
    ): ManagedMigrationReplacementJournal? {
        return synchronized(requestMutationLock) {
            if (!isRequestCurrentLocked(ownerWorkId)) return@synchronized null
            recordReplacementJournalLocked(journal)
        }
    }

    @SuppressLint("UseKtx")
    private fun recordReplacementJournalLocked(
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

    private fun isRequestCurrentLocked(workId: String): Boolean {
        val normalizedWorkId = workId.trim().takeIf(String::isNotBlank) ?: return false
        return migrationWorkIdsEqual(readRequest()?.workId, normalizedWorkId)
    }

    private fun containsEquivalentWorkId(
        workId: String?,
        candidates: Collection<String>
    ): Boolean {
        return candidates.any { candidate ->
            migrationWorkIdsEqual(workId, candidate)
        }
    }

    @SuppressLint("UseKtx")
    fun clearReplacementJournal(): Boolean {
        return preferences.edit().remove(ACTIVE_REPLACEMENT_JOURNAL_KEY).commit()
    }

    @SuppressLint("UseKtx")
    fun clear(workId: String): Boolean {
        return synchronized(copyReceiptMutationLock) {
            val editor = preferences.edit()
                .remove(keyFor(workId))
                .remove(targetNamesKeyFor(workId))
                .remove(progressKeyFor(workId))
            copyReceiptKeysFor(workId).forEach(editor::remove)
            editor.remove(copyReceiptIndexKeyFor(workId))
            editor.commit()
        }
    }

    private fun keyFor(workId: String): String = "$KEY_PREFIX$workId"

    private fun migrationRootsDiffer(
        currentFromDirectoryUri: String?,
        currentToDirectoryUri: String?,
        requestedFromDirectoryUri: String?,
        requestedToDirectoryUri: String?
    ): Boolean {
        return !ManagedDownloadStorage.areEquivalentDirectoryUris(
            currentFromDirectoryUri,
            requestedFromDirectoryUri
        ) || !ManagedDownloadStorage.areEquivalentDirectoryUris(
            currentToDirectoryUri,
            requestedToDirectoryUri
        )
    }

    private fun migrationRequestsEqual(
        current: ManagedMigrationRequest?,
        expected: ManagedMigrationRequest
    ): Boolean {
        current ?: return false
        val normalizedCurrent = current.normalized()
        val normalizedExpected = expected.normalized()
        return migrationWorkIdsEqual(
            normalizedCurrent.workId,
            normalizedExpected.workId
        ) &&
            ManagedDownloadStorage.areEquivalentDirectoryUris(
                normalizedCurrent.fromDirectoryUri,
                normalizedExpected.fromDirectoryUri
            ) &&
            ManagedDownloadStorage.areEquivalentDirectoryUris(
                normalizedCurrent.toDirectoryUri,
                normalizedExpected.toDirectoryUri
            ) &&
            normalizedCurrent.targetLabel == normalizedExpected.targetLabel &&
            normalizedCurrent.releasePreviousPermission ==
                normalizedExpected.releasePreviousPermission &&
            normalizedCurrent.minimumSourceEntryCount ==
                normalizedExpected.minimumSourceEntryCount &&
            nullableMigrationWorkIdsEqual(
                normalizedCurrent.checkpointWorkId,
                normalizedExpected.checkpointWorkId
            ) &&
            normalizedCurrent.autoResume == normalizedExpected.autoResume &&
            normalizedCurrent.retryAttemptOffset == normalizedExpected.retryAttemptOffset
    }

    private fun nullableMigrationWorkIdsEqual(
        left: String?,
        right: String?
    ): Boolean {
        return left == null && right == null || migrationWorkIdsEqual(left, right)
    }

    private fun archivedRequestKeyFor(archiveId: String): String {
        return "$ARCHIVED_REQUEST_KEY_PREFIX$archiveId"
    }

    private fun archivedJournalKeyFor(archiveId: String): String {
        return "$ARCHIVED_REPLACEMENT_JOURNAL_KEY_PREFIX$archiveId"
    }

    @SuppressLint("UseKtx")
    private fun writeRequestLocked(request: ManagedMigrationRequest): ManagedMigrationRequest {
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

    private fun targetNamesKeyFor(workId: String): String = "$TARGET_NAMES_KEY_PREFIX$workId"

    private fun progressKeyFor(workId: String): String = "$PROGRESS_KEY_PREFIX$workId"

    private fun copyReceiptKeyPrefixFor(workId: String): String {
        return "$COPY_RECEIPT_KEY_PREFIX$workId:"
    }

    private fun copyReceiptIndexKeyFor(workId: String): String {
        return "$COPY_RECEIPT_INDEX_KEY_PREFIX${workId.trim()}"
    }

    private fun copyReceiptKeyFor(workId: String, sourceReference: String): String {
        return copyReceiptKeyPrefixFor(workId.trim()) + copyReceiptKeySuffixFor(sourceReference)
    }

    private fun copyReceiptKeySuffixFor(sourceReference: String): String {
        return sha256Key(sourceReference.trim())
    }

    private fun copyReceiptKeysFor(workId: String): Set<String> {
        val normalizedWorkId = workId.trim()
        if (normalizedWorkId.isBlank()) return emptySet()
        val prefix = copyReceiptKeyPrefixFor(normalizedWorkId)
        val indexedSuffixes = readIndexedCopyReceiptSuffixes(normalizedWorkId)
        val indexedKeys = indexedSuffixes
            ?.mapTo(linkedSetOf()) { suffix -> prefix + suffix }
            .orEmpty()
        if (
            indexedSuffixes != null &&
                indexedKeys.all { key -> preferences.getString(key, null)?.isNotBlank() == true }
        ) {
            return indexedKeys
        }
        // 缺失或不完整的索引只能交给兼容扫描恢复
        return indexedKeys + scanCopyReceiptKeys(normalizedWorkId)
    }

    private fun decodeCopyReceiptAtKey(key: String): ManagedMigrationCopyReceipt? {
        return preferences.getString(key, null)
            ?.takeIf(String::isNotBlank)
            ?.let { raw ->
                runCatching { decodeCopyReceipt(JSONObject(raw)) }.getOrNull()
            }
    }

    private fun reliableCopyReceiptSuffixes(workId: String): Set<String> {
        val indexed = readIndexedCopyReceiptSuffixes(workId)
        if (indexed == null) return scanCopyReceiptSuffixes(workId)
        if (indexed.isEmpty()) return indexed
        val prefix = copyReceiptKeyPrefixFor(workId)
        val indexIsUsable = indexed.all { suffix ->
            decodeCopyReceiptAtKey(prefix + suffix) != null
        }
        return if (indexIsUsable) indexed else scanCopyReceiptSuffixes(workId)
    }

    private fun scanCopyReceiptKeys(workId: String): Set<String> {
        val prefix = copyReceiptKeyPrefixFor(workId.trim())
        return runCatching {
            preferences.all.keys
                .filter { key -> key.startsWith(prefix) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun scanCopyReceiptSuffixes(workId: String): Set<String> {
        val prefix = copyReceiptKeyPrefixFor(workId.trim())
        return runCatching {
            preferences.all.keys
                .asSequence()
                .filter { key -> key.startsWith(prefix) }
                .map { key -> key.removePrefix(prefix) }
                .filter(::isSha256Digest)
                .toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * 索引缺失或损坏时返回 null，让旧凭据键继续通过兼容扫描恢复
     */
    private fun readIndexedCopyReceiptSuffixes(workId: String): Set<String>? {
        val raw = runCatching {
            preferences.getString(copyReceiptIndexKeyFor(workId), null)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        return try {
            val array = JSONArray(raw)
            val suffixes = linkedSetOf<String>()
            for (index in 0 until array.length()) {
                val suffix = array.opt(index)
                if (suffix !is String || !isSha256Digest(suffix)) return null
                suffixes += suffix
            }
            suffixes
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeCopyReceiptIndex(suffixes: Collection<String>): String {
        return JSONArray().apply {
            suffixes
                .asSequence()
                .filter(::isSha256Digest)
                .distinct()
                .sorted()
                .forEach(::put)
        }.toString()
    }

    private fun sha256Key(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

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
            put("retryAttemptOffset", request.retryAttemptOffset.coerceAtLeast(0))
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
            autoResume = root.optBoolean("autoResume", true),
            retryAttemptOffset = root.optInt("retryAttemptOffset", 0).coerceAtLeast(0)
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
            put("cleanupComplete", journal.cleanupComplete)
            put("sourceEntryCount", journal.sourceEntryCount)
            put("deletedSourceAudioCount", journal.deletedSourceAudioCount.coerceAtLeast(0))
            put("sourceEntriesComplete", journal.sourceEntriesComplete)
            put("cleanupReceipts", org.json.JSONArray().apply {
                journal.cleanupReceipts.sortedWith(
                    compareBy<ManagedMigrationCleanupReceipt>(
                        { it.sourceSubdirectory.orEmpty() },
                        { it.targetEntry.name },
                        { it.sourceReference }
                    )
                ).forEach { receipt ->
                    put(encodeCleanupReceipt(receipt))
                }
            })
            put("sourceEntries", org.json.JSONArray().apply {
                journal.sourceEntries.sortedWith(
                    compareBy<ManagedMigrationSourceEntry>(
                        { it.sourceSubdirectory.orEmpty() },
                        { it.sourceName },
                        { it.sourceReference }
                    )
                ).forEach { entry ->
                    put(encodeSourceEntry(entry))
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

    private fun encodeCopyReceipt(receipt: ManagedMigrationCopyReceipt): JSONObject {
        return JSONObject().apply {
            put("version", CURRENT_MIGRATION_COPY_RECEIPT_VERSION)
            put("sourceReference", receipt.sourceReference)
            put("sourceName", receipt.sourceName)
            put("sourceSubdirectory", receipt.sourceSubdirectory)
            put("sourceSizeBytes", receipt.sourceSizeBytes)
            put("sourceLastModifiedMs", receipt.sourceLastModifiedMs)
            put("targetEntry", encodeStoredEntry(receipt.targetEntry))
            put("sourceDigest", receipt.sourceDigest)
            put("verifiedTargetDigest", receipt.verifiedTargetDigest)
            put("createdNew", receipt.createdNew)
            put("sourceAuthoritative", receipt.sourceAuthoritative)
            put("replacementBackup", receipt.replacementBackup?.let(::encodeStoredEntry))
            put("sourceLogicalCreatedAtMs", receipt.sourceLogicalCreatedAtMs)
            put("sourceCreatedAtSource", receipt.sourceCreatedAtSource)
            put("sourceCreatedAtConfidence", receipt.sourceCreatedAtConfidence)
        }
    }

    private fun encodeCleanupReceipt(
        receipt: ManagedMigrationCleanupReceipt
    ): JSONObject {
        return JSONObject().apply {
            put("sourceReference", receipt.sourceReference)
            put("sourceName", receipt.sourceName)
            put("sourceSubdirectory", receipt.sourceSubdirectory)
            put("targetDigest", receipt.targetDigest)
            put("targetEntry", encodeStoredEntry(receipt.targetEntry))
            put("sourceLogicalCreatedAtMs", receipt.sourceLogicalCreatedAtMs)
            put("sourceCreatedAtSource", receipt.sourceCreatedAtSource)
            put("sourceCreatedAtConfidence", receipt.sourceCreatedAtConfidence)
        }
    }

    private fun encodeSourceEntry(
        entry: ManagedMigrationSourceEntry
    ): JSONObject {
        return JSONObject().apply {
            put("sourceReference", entry.sourceReference)
            put("sourceName", entry.sourceName)
            put("sourceSubdirectory", entry.sourceSubdirectory)
            put("sizeBytes", entry.sizeBytes)
            put("lastModifiedMs", entry.lastModifiedMs)
            put("logicalCreatedAtMs", entry.logicalCreatedAtMs)
            put("createdAtSource", entry.createdAtSource)
            put("createdAtConfidence", entry.createdAtConfidence)
        }
    }

    private fun decodeReplacementJournal(root: JSONObject): ManagedMigrationReplacementJournal {
        val workId = root.optString("workId").trim().takeIf(String::isNotBlank)
            ?: invalidJournal("迁移替换事务缺少 workId")
        val version = root.optInt(
            "version",
            CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION
        )
        if (
            version < MIN_SUPPORTED_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION ||
            version > CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION
        ) {
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
        val replacements = buildList {
            for (index in 0 until array.length()) {
                decodeReplacementPlan(array.optJSONObject(index))
                    ?.let(::add)
                    ?: invalidJournal("迁移替换事务包含无效 replacement: index=$index")
            }
        }
        val targetNames = decodeTargetNames(root.optJSONObject("targetNames"))
        val cleanupReceipts = decodeCleanupReceipts(root.optJSONArray("cleanupReceipts"))
        val sourceEntries = decodeSourceEntries(root.optJSONArray("sourceEntries"))
        val sourceEntryCount = root.optInt("sourceEntryCount", 0).coerceAtLeast(0)
        val deletedSourceAudioCount = root.optInt(
            "deletedSourceAudioCount",
            0
        ).coerceAtLeast(0)
        val sourceEntriesComplete = if (root.has("sourceEntriesComplete")) {
            root.optBoolean("sourceEntriesComplete", false)
        } else {
            // 显式标记出现前写入的 v2 日志，只要清单非空仍可推断为完整
            sourceEntryCount > 0 || sourceEntries.isNotEmpty() || deletedSourceAudioCount > 0
        }
        return ManagedMigrationReplacementJournal(
            version = version,
            workId = workId,
            fromDirectoryUri = root.optNullableString("fromDirectoryUri"),
            toDirectoryUri = root.optNullableString("toDirectoryUri"),
            backupNamespace = backupNamespace,
            phase = phase,
            replacements = replacements,
            targetNamesByReference = targetNames,
            cleanupReceipts = cleanupReceipts,
            cleanupComplete = root.optBoolean("cleanupComplete", false),
            sourceEntryCount = sourceEntryCount,
            sourceEntries = sourceEntries,
            deletedSourceAudioCount = deletedSourceAudioCount,
            sourceEntriesComplete = sourceEntriesComplete
        )
    }

    private fun decodeSourceEntries(
        array: org.json.JSONArray?
    ): List<ManagedMigrationSourceEntry> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index)
                    ?: invalidJournal("迁移源清单包含无效条目: index=$index")
                val sourceReference = value.optString("sourceReference").trim()
                val sourceName = value.optString("sourceName").trim()
                val sourceSubdirectory = value.optNullableString("sourceSubdirectory")
                val entry = ManagedMigrationSourceEntry(
                    sourceReference = sourceReference,
                    sourceName = sourceName,
                    sourceSubdirectory = sourceSubdirectory,
                    sizeBytes = value.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                    lastModifiedMs = value.optLong("lastModifiedMs", 0L).coerceAtLeast(0L),
                    logicalCreatedAtMs = value.optLong("logicalCreatedAtMs", 0L)
                        .takeIf { value.has("logicalCreatedAtMs") && it > 0L },
                    createdAtSource = value.optNullableString("createdAtSource"),
                    createdAtConfidence = value.optNullableString("createdAtConfidence")
                )
                if (
                    entry.sourceReference.isBlank() ||
                    !isSafeMigrationPlanName(entry.sourceName) ||
                    entry.sourceSubdirectory != null &&
                        entry.sourceSubdirectory !=
                            moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY &&
                        entry.sourceSubdirectory !=
                            moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
                    || !isValidMigrationCreatedAtMetadata(
                        timestampMs = entry.logicalCreatedAtMs,
                        source = entry.createdAtSource,
                        confidence = entry.createdAtConfidence
                    )
                ) {
                    invalidJournal("迁移源清单包含无效条目: $sourceReference")
                }
                add(entry)
            }
        }
    }

    private fun decodeCleanupReceipts(
        array: org.json.JSONArray?
    ): List<ManagedMigrationCleanupReceipt> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index)
                    ?: invalidJournal("迁移清理凭据包含无效条目: index=$index")
                val sourceReference = value.optString("sourceReference").trim()
                val sourceName = value.optString("sourceName").trim()
                val targetDigest = value.optString("targetDigest").trim()
                val targetEntry = decodeStoredEntry(value.optJSONObject("targetEntry"))
                    ?: invalidJournal("迁移清理凭据缺少目标条目: index=$index")
                val receipt = ManagedMigrationCleanupReceipt(
                    sourceReference = sourceReference,
                    sourceName = sourceName,
                    sourceSubdirectory = value.optNullableString("sourceSubdirectory"),
                    targetEntry = targetEntry,
                    targetDigest = targetDigest,
                    sourceLogicalCreatedAtMs = value.optLong(
                        "sourceLogicalCreatedAtMs",
                        0L
                    ).takeIf { value.has("sourceLogicalCreatedAtMs") && it > 0L },
                    sourceCreatedAtSource = value.optNullableString("sourceCreatedAtSource"),
                    sourceCreatedAtConfidence = value.optNullableString(
                        "sourceCreatedAtConfidence"
                    )
                )
                validateDecodedCleanupReceipt(receipt)
                add(receipt)
            }
        }
    }

    private fun validateDecodedCleanupReceipt(
        receipt: ManagedMigrationCleanupReceipt
    ) {
        if (
            receipt.sourceReference.isBlank() ||
            receipt.sourceName.isBlank() ||
            !isSafeMigrationPlanName(receipt.targetEntry.name) ||
            receipt.targetEntry.isDirectory ||
            receipt.sourceSubdirectory != null &&
                receipt.sourceSubdirectory !=
                    moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY &&
                receipt.sourceSubdirectory !=
                    moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY ||
            receipt.targetDigest.length != 64 ||
            receipt.targetDigest.any { character ->
                character !in '0'..'9' && character !in 'a'..'f' &&
                    character !in 'A'..'F'
            } || !isValidMigrationCreatedAtMetadata(
                timestampMs = receipt.sourceLogicalCreatedAtMs,
                source = receipt.sourceCreatedAtSource,
                confidence = receipt.sourceCreatedAtConfidence
            )
        ) {
            invalidJournal("迁移清理凭据包含无效条目: ${receipt.sourceReference}")
        }
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

    private fun decodeCopyReceipt(root: JSONObject): ManagedMigrationCopyReceipt {
        if (
            root.optInt("version", CURRENT_MIGRATION_COPY_RECEIPT_VERSION) !=
                CURRENT_MIGRATION_COPY_RECEIPT_VERSION
        ) {
            throw IllegalArgumentException("迁移复制凭据版本不受支持")
        }
        val targetEntry = decodeStoredEntry(root.optJSONObject("targetEntry"))
            ?: throw IllegalArgumentException("迁移复制凭据缺少目标条目")
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = root.optString("sourceReference").trim(),
            sourceName = root.optString("sourceName").trim(),
            sourceSubdirectory = root.optNullableString("sourceSubdirectory"),
            sourceSizeBytes = root.optLong("sourceSizeBytes", 0L),
            sourceLastModifiedMs = root.optLong("sourceLastModifiedMs", 0L),
            targetEntry = targetEntry,
            sourceDigest = root.optNullableString("sourceDigest"),
            verifiedTargetDigest = root.optNullableString("verifiedTargetDigest"),
            createdNew = root.optBoolean("createdNew", false),
            sourceAuthoritative = root.optBoolean("sourceAuthoritative", false),
            replacementBackup = decodeStoredEntry(root.optJSONObject("replacementBackup")),
            sourceLogicalCreatedAtMs = root.optLong("sourceLogicalCreatedAtMs", 0L)
                .takeIf { root.has("sourceLogicalCreatedAtMs") && it > 0L },
            sourceCreatedAtSource = root.optNullableString("sourceCreatedAtSource"),
            sourceCreatedAtConfidence = root.optNullableString("sourceCreatedAtConfidence")
        )
        validateCopyReceipt(receipt)
        return receipt
    }

    private fun validateCopyReceipt(receipt: ManagedMigrationCopyReceipt) {
        fun validateStoredEntry(
            entry: moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
        ) {
            if (
                !isSafeMigrationPlanName(entry.name) ||
                entry.reference.isBlank() ||
                entry.mediaUri.isBlank() ||
                entry.isDirectory ||
                entry.sizeBytes < 0L ||
                entry.lastModifiedMs < 0L
            ) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移复制凭据包含无效目标条目: ${entry.name}"
                )
            }
        }
        if (
            receipt.sourceReference.isBlank() ||
            !isSafeMigrationPlanName(receipt.sourceName) ||
            receipt.sourceSizeBytes < 0L ||
            receipt.sourceLastModifiedMs < 0L ||
            receipt.sourceSubdirectory != null &&
                receipt.sourceSubdirectory !=
                    moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY &&
                receipt.sourceSubdirectory !=
                    moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY ||
            receipt.sourceDigest?.let(::isSha256Digest) == false
                || receipt.verifiedTargetDigest?.let(::isSha256Digest) == false
                || !isValidMigrationCreatedAtMetadata(
                    timestampMs = receipt.sourceLogicalCreatedAtMs,
                    source = receipt.sourceCreatedAtSource,
                    confidence = receipt.sourceCreatedAtConfidence
                )
        ) {
            throw ManagedDownloadMigrationException.transient(
                "迁移复制凭据包含无效源条目: ${receipt.sourceReference}"
            )
        }
        validateStoredEntry(receipt.targetEntry)
        receipt.replacementBackup?.let(::validateStoredEntry)
    }

    private fun isSha256Digest(value: String): Boolean {
        return value.length == 64 && value.all { character ->
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }
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
        internal const val COPY_RECEIPT_KEY_PREFIX = "copy_receipt:"
        internal const val COPY_RECEIPT_INDEX_KEY_PREFIX = "copy_receipt_index:"
        internal const val ACTIVE_REQUEST_KEY = "request:active"
        internal const val ACTIVE_REPLACEMENT_JOURNAL_KEY = "replacement_journal:active"
        internal const val ARCHIVED_REQUEST_KEY_PREFIX = "request:archive:"
        internal const val ARCHIVED_REPLACEMENT_JOURNAL_KEY_PREFIX =
            "replacement_journal:archive:"
        internal const val CURRENT_MIGRATION_REQUEST_VERSION = 1
        internal const val CURRENT_MIGRATION_PROGRESS_VERSION = 1
        internal const val CURRENT_MIGRATION_COPY_RECEIPT_VERSION = 1
        internal const val MIN_SUPPORTED_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION = 1

        private val copyReceiptMutationLock = Any()
        private val requestMutationLock = Any()
    }
}
