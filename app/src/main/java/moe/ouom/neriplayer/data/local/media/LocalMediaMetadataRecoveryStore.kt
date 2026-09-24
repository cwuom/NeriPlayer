package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val METADATA_RECOVERY_VERSION = 1
private const val METADATA_RECOVERY_DIRECTORY = "staged_metadata_writes"
private const val METADATA_RECOVERY_PREFIX = "recovery-"
private const val METADATA_RECOVERY_SUFFIX = ".json"

internal enum class LocalMetadataRecoveryStage {
    PREPARED,
    REPLACING,
    TARGET_VERIFIED,
    ROLLED_BACK,
    ROLLBACK_FAILED
}

internal data class LocalMetadataRecoveryRecord(
    val id: String,
    val targetReference: String,
    val backupFile: File,
    val updatedFile: File,
    val originalSha256: String,
    val updatedSha256: String,
    val originalLastModifiedMs: Long?,
    val stage: LocalMetadataRecoveryStage,
    val journalFile: File,
    val companionTransaction: Boolean = false,
    val companionCommitted: Boolean = false,
    val audioUnchanged: Boolean = false,
    val companions: List<LocalMediaCompanionRecoveryEntry> = emptyList()
)

internal object LocalMediaMetadataRecoveryStore {
    private const val TAG = "LocalMetadataRecovery"
    private val recoveryMutex = Mutex()
    private val activeRecordIds = ConcurrentHashMap.newKeySet<String>()
    private val reservedTargetKeys = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val recordGeneration = AtomicLong()
    @Volatile
    private var recoveryCompleted = false
    @Volatile
    private var completedRecoveryGeneration = -1L
    @Volatile
    private var completedRecoveryDirectory: String? = null

    fun stagingDirectory(context: Context): File =
        File(context.noBackupFilesDir, METADATA_RECOVERY_DIRECTORY)

    fun begin(
        context: Context,
        targetReference: String,
        backupFile: File,
        updatedFile: File,
        originalLastModifiedMs: Long?,
        companionTransaction: Boolean = false,
        audioUnchanged: Boolean = false
    ): LocalMetadataRecoveryRecord {
        require(backupFile.isFile && (backupFile.length() > 0L || companionTransaction && audioUnchanged))
        require(updatedFile.isFile && (updatedFile.length() > 0L || companionTransaction && audioUnchanged))
        val directory = stagingDirectory(context)
        require(directory.exists() || directory.mkdirs())
        val id = UUID.randomUUID().toString()
        val record = LocalMetadataRecoveryRecord(
            id = id,
            targetReference = targetReference,
            backupFile = backupFile,
            updatedFile = updatedFile,
            originalSha256 = sha256(backupFile),
            updatedSha256 = sha256(updatedFile),
            originalLastModifiedMs = originalLastModifiedMs,
            stage = LocalMetadataRecoveryStage.PREPARED,
            journalFile = File(directory, "$METADATA_RECOVERY_PREFIX$id$METADATA_RECOVERY_SUFFIX"),
            companionTransaction = companionTransaction,
            audioUnchanged = audioUnchanged
        )
        recordGeneration.incrementAndGet()
        activeRecordIds += id
        try {
            persist(record)
        } catch (error: Throwable) {
            activeRecordIds -= id
            throw error
        }
        recoveryCompleted = false
        return record
    }

    fun markReplacing(record: LocalMetadataRecoveryRecord): LocalMetadataRecoveryRecord =
        updateStage(record, LocalMetadataRecoveryStage.REPLACING)

    fun markTargetVerified(record: LocalMetadataRecoveryRecord): LocalMetadataRecoveryRecord =
        updateStage(record, LocalMetadataRecoveryStage.TARGET_VERIFIED)

    fun complete(record: LocalMetadataRecoveryRecord) {
        if (record.stage != LocalMetadataRecoveryStage.TARGET_VERIFIED) {
            throw IllegalStateException("metadata target was not verified")
        }
        check(!record.companionTransaction || record.companionCommitted)
        cleanup(record)
    }

    internal fun saveCompanionRecord(record: LocalMetadataRecoveryRecord): LocalMetadataRecoveryRecord {
        check(record.companionTransaction)
        persist(record)
        recoveryCompleted = false
        return record
    }

    internal fun releaseCompanionRecord(record: LocalMetadataRecoveryRecord) {
        activeRecordIds -= record.id
        recoveryCompleted = false
    }

    internal fun updatedCompanionAudio(record: LocalMetadataRecoveryRecord): LocalMetadataRecoveryRecord =
        saveCompanionRecord(record.copy(updatedSha256 = sha256(record.updatedFile)))

    fun rollback(
        context: Context,
        record: LocalMetadataRecoveryRecord,
        onCompanionsUpdated: (LocalMetadataRecoveryRecord) -> Unit = {}
    ): Boolean {
        if (record.companionCommitted) {
            releaseCompanionRecord(record)
            return false
        }
        val companionResult = if (record.companionTransaction) rollbackLocalMediaCompanions(context, record)
            else CompanionRollbackResult(record, true)
        val latestRecord = companionResult.record
        onCompanionsUpdated(latestRecord)
        val companionsRestored = companionResult.restored
        val audioRestored = if (record.audioUnchanged) {
            true
        } else if (targetMatches(context, record.targetReference, record.originalSha256)) {
            val targetFile = directFile(record.targetReference)
            val originalTime = record.originalLastModifiedMs?.takeIf { it > 0L }
            targetFile == null || originalTime == null || runCatching {
                targetFile.lastModified() == originalTime || targetFile.setLastModified(originalTime)
            }.getOrDefault(false)
        } else {
            replaceTargetFromFile(
                context = context,
                targetReference = record.targetReference,
                source = record.backupFile,
                lastModifiedMs = record.originalLastModifiedMs
            ) && targetMatches(context, record.targetReference, record.originalSha256)
        }
        val restored = companionsRestored && audioRestored
        if (restored) {
            val rolledBack = runCatching {
                updateStage(latestRecord, LocalMetadataRecoveryStage.ROLLED_BACK)
            }.onFailure { error ->
                NPLogger.e(TAG, "记录元信息已回滚状态失败，保留恢复文件", error)
            }.getOrNull() ?: return false
            return cleanup(rolledBack)
        } else {
            runCatching {
                updateStage(latestRecord, LocalMetadataRecoveryStage.ROLLBACK_FAILED)
            }.onFailure { error ->
                NPLogger.e(TAG, "记录元信息回滚失败状态失败，保留恢复文件", error)
            }
            activeRecordIds -= record.id
            recoveryCompleted = false
        }
        return false
    }

    internal suspend fun <T> withRecoveredTargets(
        context: Context,
        targetReferences: Collection<String>,
        blockedResult: T,
        write: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val targetKeys = targetReferences.map { reference ->
            targetIdentity(reference) ?: return@withContext blockedResult
        }.toSet()
        if (targetKeys.isEmpty()) return@withContext blockedResult
        var reservation: CompletableDeferred<Unit>? = null
        try {
            while (reservation == null) {
                var blocked = false
                val waitFor = recoveryMutex.withLock {
                    val existing = targetKeys.firstNotNullOfOrNull(reservedTargetKeys::get)
                    if (existing == null) {
                        if (!recoverRecordsLocked(context, targetKeys).allResolved) {
                            blocked = true
                        } else {
                            // 恢复与预约必须原子完成，避免新 journal 建立前被旧恢复抢占
                            val acquired = CompletableDeferred<Unit>()
                            targetKeys.forEach { key -> reservedTargetKeys[key] = acquired }
                            reservation = acquired
                        }
                    }
                    existing
                }
                if (blocked) return@withContext blockedResult
                // 正常并发编辑按目标排队，等待时不占用全局恢复锁
                waitFor?.await()
            }
            write()
        } finally {
            reservation?.let { acquired ->
                withContext(NonCancellable) {
                    recoveryMutex.withLock {
                        targetKeys.forEach { key ->
                            if (reservedTargetKeys[key] === acquired) reservedTargetKeys.remove(key)
                        }
                        acquired.complete(Unit)
                    }
                }
            }
        }
    }

    suspend fun recoverInterruptedWrites(context: Context): Int = withContext(Dispatchers.IO) {
        recoveryMutex.withLock {
            val directoryPath = stagingDirectory(context).absolutePath
            if (recoveryCompleted && completedRecoveryDirectory == directoryPath &&
                completedRecoveryGeneration == recordGeneration.get()
            ) return@withLock 0
            val generationAtStart = recordGeneration.get()
            val result = recoverRecordsLocked(context, targetKeys = null)
            completedRecoveryDirectory = directoryPath
            completedRecoveryGeneration = generationAtStart
            recoveryCompleted = result.allResolved && reservedTargetKeys.isEmpty() &&
                activeRecordIds.isEmpty() && generationAtStart == recordGeneration.get()
            result.recoveredCount
        }
    }

    private data class RecoveryPass(val recoveredCount: Int, val allResolved: Boolean)

    private suspend fun recoverRecordsLocked(context: Context, targetKeys: Set<String>?): RecoveryPass {
        val directory = stagingDirectory(context)
        if (!directory.exists()) return RecoveryPass(0, true)
        val journals = directory.listFiles { file ->
            file.isFile && file.name.startsWith(METADATA_RECOVERY_PREFIX) &&
                file.name.endsWith(METADATA_RECOVERY_SUFFIX)
        } ?: return RecoveryPass(0, false)
        var recoveredCount = 0
        var allResolved = true
        val candidates = linkedMapOf<String, MutableList<Pair<File, JSONObject>>>()
        journals.forEach { journal ->
            currentCoroutineContext().ensureActive()
            val body = runCatching { JSONObject(journal.readText()) }.getOrNull()
            val targetKey = body?.optString("targetReference")?.let(::targetIdentity)
            if (body == null || targetKey == null) {
                // 无法确认目标的凭据不能当成与本次编辑无关
                NPLogger.w(TAG, "元信息恢复凭据目标未知，保留凭据并阻止新编辑: ${journal.name}")
                allResolved = false
                return@forEach
            }
            if (targetKeys != null && targetKey !in targetKeys) return@forEach
            candidates.getOrPut(targetKey) { mutableListOf() }.add(journal to body)
        }
        // 未知目标可能属于任何已知记录的后继写入，不能先回放再拒绝新编辑
        if (!allResolved) return RecoveryPass(0, false)
        candidates.forEach { (targetKey, records) ->
            currentCoroutineContext().ensureActive()
            if (records.size != 1) {
                // 旧版本可能留下多个写入意图，文件时间或随机 id 都不能证明提交顺序
                NPLogger.w(TAG, "同一音频存在多个元信息恢复凭据，保留原内容与备份: count=${records.size}")
                allResolved = false
                return@forEach
            }
            val (journal, body) = records.single()
            val record = runCatching { readRecord(context, journal, body) }
                .onFailure { error ->
                    NPLogger.e(TAG, "读取元信息恢复凭据失败，原文件和备份均保留: ${journal.name}", error)
                }
                .getOrNull()
            if (record == null || record.id in activeRecordIds || targetKey in reservedTargetKeys) {
                allResolved = false
                return@forEach
            }
            if (recoverRecord(context, record)) recoveredCount++ else allResolved = false
        }
        return RecoveryPass(recoveredCount, allResolved)
    }

    internal fun targetIdentity(reference: String): String? {
        val raw = reference.takeIf(String::isNotBlank) ?: return null
        if (raw.startsWith('/')) return runCatching { "file:${File(raw).canonicalPath}" }.getOrNull()
        if (raw.startsWith("file:", ignoreCase = true)) {
            return runCatching {
                URI(raw).path?.takeIf { it.startsWith('/') }
                    ?.let { path -> "file:${File(path).canonicalPath}" }
            }.getOrNull()
        }
        val uri = runCatching { raw.toUri() }.getOrNull() ?: return null
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        val authority = uri.authority?.takeIf(String::isNotBlank) ?: return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return if (documentId != null) {
            "document:${authority.length}:$authority:$documentId"
        } else {
            "uri:${uri.normalizeScheme()}"
        }
    }

    internal fun resetRecoveryForTest() {
        recoveryCompleted = false
        completedRecoveryDirectory = null
        activeRecordIds.clear()
        reservedTargetKeys.clear()
    }

    fun targetMatches(context: Context, targetReference: String, expectedSha256: String): Boolean {
        return runCatching {
            openTargetInput(context, targetReference)?.use { input ->
                sha256(input) == expectedSha256
            } == true
        }.getOrDefault(false)
    }

    fun replaceTargetFromFile(
        context: Context,
        targetReference: String,
        source: File,
        lastModifiedMs: Long? = null
    ): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        val directFile = directFile(targetReference)
        val replaced = if (directFile != null) {
            replaceDirectFile(source, directFile)
        } else {
            val uri = targetReference.toUri()
            replaceContentUri(context, uri, source)
        }
        if (replaced && directFile != null && lastModifiedMs != null && lastModifiedMs > 0L) {
            if (!directFile.setLastModified(lastModifiedMs)) {
                NPLogger.w(TAG, "元信息替换后无法恢复文件修改时间: ${directFile.absolutePath}")
                return false
            }
        }
        return replaced
    }

    private fun recoverRecord(context: Context, record: LocalMetadataRecoveryRecord): Boolean {
        if (record.companionTransaction) {
            if (!record.companionCommitted) return rollback(context, record)
            val audioVerified = record.audioUnchanged || targetMatches(context, record.targetReference, record.updatedSha256)
            return audioVerified && finishLocalMediaCompanionDeletes(context, record) && cleanup(record)
        }
        val targetMatchesOriginal = targetMatches(
            context,
            record.targetReference,
            record.originalSha256
        )
        val targetMatchesUpdated = targetMatches(
            context,
            record.targetReference,
            record.updatedSha256
        )
        when (record.stage) {
            LocalMetadataRecoveryStage.PREPARED -> {
                // PREPARED 尚未获得修改目标的许可，目标身份变化时不能覆盖新 owner
                return targetMatchesOriginal && cleanup(record)
            }
            LocalMetadataRecoveryStage.TARGET_VERIFIED -> {
                // 已验证后又发生变化说明 URI 可能被外部改写，保留凭据等待人工处理
                return targetMatchesUpdated && cleanup(record)
            }
            LocalMetadataRecoveryStage.ROLLED_BACK -> {
                // 回滚已经落盘，重启后只能清理凭据，不能再次应用待写入内容
                return targetMatchesOriginal && cleanup(record)
            }
            LocalMetadataRecoveryStage.ROLLBACK_FAILED -> return rollback(context, record)
            LocalMetadataRecoveryStage.REPLACING -> Unit
        }
        if (targetMatchesUpdated) {
            return cleanup(record)
        }
        val updatedValid = record.updatedFile.isFile &&
            runCatching { sha256(record.updatedFile) == record.updatedSha256 }.getOrDefault(false)
        if (updatedValid && replaceTargetFromFile(
                context = context,
                targetReference = record.targetReference,
                source = record.updatedFile,
                lastModifiedMs = record.originalLastModifiedMs
            ) && targetMatches(context, record.targetReference, record.updatedSha256)
        ) {
            return cleanup(record)
        }
        return rollback(context, record)
    }

    private fun updateStage(
        record: LocalMetadataRecoveryRecord,
        stage: LocalMetadataRecoveryStage
    ): LocalMetadataRecoveryRecord {
        val updated = record.copy(stage = stage)
        persist(updated)
        return updated
    }

    private fun persist(record: LocalMetadataRecoveryRecord) {
        val body = JSONObject().apply {
            put("version", METADATA_RECOVERY_VERSION)
            put("id", record.id)
            put("targetReference", record.targetReference)
            put("backupPath", record.backupFile.absolutePath)
            put("updatedPath", record.updatedFile.absolutePath)
            put("originalSha256", record.originalSha256)
            put("updatedSha256", record.updatedSha256)
            put("originalLastModifiedMs", record.originalLastModifiedMs ?: JSONObject.NULL)
            put("stage", record.stage.name)
            if (record.companionTransaction) {
                put("companionTransaction", true)
                put("companionCommitted", record.companionCommitted)
                put("audioUnchanged", record.audioUnchanged)
                put("companions", JSONArray().apply { record.companions.forEach { put(it.toJson()) } })
            }
        }
        ManagedDownloadAtomicFile.writeTextAtomically(record.journalFile, body.toString())
    }

    private fun readRecord(
        context: Context,
        journalFile: File,
        body: JSONObject
    ): LocalMetadataRecoveryRecord {
        require(body.getInt("version") == METADATA_RECOVERY_VERSION)
        val directory = stagingDirectory(context).canonicalFile
        fun validatedStagingFile(key: String): File {
            val file = File(body.getString(key)).canonicalFile
            require(file.parentFile == directory)
            return file
        }
        return LocalMetadataRecoveryRecord(
            id = body.getString("id"),
            targetReference = body.getString("targetReference"),
            backupFile = validatedStagingFile("backupPath"),
            updatedFile = validatedStagingFile("updatedPath"),
            originalSha256 = body.getString("originalSha256"),
            updatedSha256 = body.getString("updatedSha256"),
            originalLastModifiedMs = body.optLong("originalLastModifiedMs")
                .takeIf { !body.isNull("originalLastModifiedMs") && it > 0L },
            stage = LocalMetadataRecoveryStage.valueOf(body.getString("stage")),
            journalFile = journalFile,
            companionTransaction = body.optBoolean("companionTransaction"),
            companionCommitted = body.optBoolean("companionCommitted"),
            audioUnchanged = body.optBoolean("audioUnchanged"),
            companions = body.optJSONArray("companions")?.let { entries ->
                List(entries.length()) { index -> LocalMediaCompanionRecoveryEntry.fromJson(entries.getJSONObject(index), directory) }
            }.orEmpty()
        )
    }

    private fun cleanup(record: LocalMetadataRecoveryRecord): Boolean {
        activeRecordIds -= record.id
        if (!record.companions.all(::cleanupCompanionStagedFile)) {
            recoveryCompleted = false
            return false
        }
        val recoveryFilesRemoved = (listOf(record.backupFile, record.updatedFile) +
            record.companions.flatMap { listOfNotNull(it.backupFile, it.intendedFile) }).all { file ->
            if (file.exists() && !file.delete()) {
                NPLogger.w(TAG, "删除已完成的元信息恢复副本失败: ${file.name}")
                false
            } else {
                true
            }
        }
        if (!recoveryFilesRemoved) {
            recoveryCompleted = false
            return false
        }
        if (record.journalFile.exists() && !record.journalFile.delete()) {
            NPLogger.w(TAG, "删除已完成的元信息恢复凭据失败: ${record.journalFile.name}")
            recoveryCompleted = false
            return false
        }
        return true
    }

    private fun directFile(targetReference: String): File? {
        if (targetReference.startsWith('/')) return File(targetReference)
        val uri = targetReference.toUri()
        val path = when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.isNullOrBlank() && uri.path?.startsWith('/') == true -> uri.path
            else -> null
        }
        return path?.let(::File)
    }

    private fun replaceDirectFile(source: File, target: File): Boolean = runCatching {
        val parent = target.parentFile ?: throw IOException("target parent is unavailable")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("target parent cannot be created")
        }
        val temporary = File(parent, ".${target.name}.npmeta-${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.onFailure { error ->
        NPLogger.e(TAG, "替换本地音频失败: ${target.absolutePath}", error)
    }.isSuccess

    private fun replaceContentUri(context: Context, uri: Uri, source: File): Boolean {
        val descriptor = openWritableDescriptor(context, uri) ?: return false
        return runCatching {
            descriptor.use { target ->
                FileInputStream(source).use { input ->
                    FileOutputStream(target.fileDescriptor).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
        }.onFailure { error ->
            NPLogger.e(TAG, "替换 SAF 音频失败: $uri", error)
        }.isSuccess
    }

    private fun openWritableDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        return listOf("rwt", "wt").firstNotNullOfOrNull { mode ->
            runCatching { context.contentResolver.openFileDescriptor(uri, mode) }.getOrNull()
        }
    }

    private fun openTargetInput(context: Context, targetReference: String) =
        directFile(targetReference)?.takeIf(File::isFile)?.inputStream()
            ?: targetReference.toUri().let(context.contentResolver::openInputStream)

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
