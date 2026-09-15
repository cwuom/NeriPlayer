package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val METADATA_RECOVERY_VERSION = 1
private const val METADATA_RECOVERY_DIRECTORY = "staged_metadata_writes"
private const val METADATA_RECOVERY_PREFIX = "recovery-"
private const val METADATA_RECOVERY_SUFFIX = ".json"

internal enum class LocalMetadataRecoveryStage {
    PREPARED,
    REPLACING,
    TARGET_VERIFIED,
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
    val journalFile: File
)

internal object LocalMediaMetadataRecoveryStore {
    private const val TAG = "LocalMetadataRecovery"
    private val recoveryMutex = Mutex()
    private val activeRecordIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var recoveryCompleted = false

    fun stagingDirectory(context: Context): File =
        File(context.noBackupFilesDir, METADATA_RECOVERY_DIRECTORY)

    fun begin(
        context: Context,
        targetReference: String,
        backupFile: File,
        updatedFile: File,
        originalLastModifiedMs: Long?
    ): LocalMetadataRecoveryRecord {
        require(backupFile.isFile && backupFile.length() > 0L)
        require(updatedFile.isFile && updatedFile.length() > 0L)
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
            journalFile = File(directory, "$METADATA_RECOVERY_PREFIX$id$METADATA_RECOVERY_SUFFIX")
        )
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
        cleanup(record)
    }

    fun rollback(context: Context, record: LocalMetadataRecoveryRecord): Boolean {
        val restored = replaceTargetFromFile(
            context = context,
            targetReference = record.targetReference,
            source = record.backupFile,
            lastModifiedMs = record.originalLastModifiedMs
        ) && targetMatches(context, record.targetReference, record.originalSha256)
        if (restored) {
            return cleanup(record)
        } else {
            runCatching {
                updateStage(record, LocalMetadataRecoveryStage.ROLLBACK_FAILED)
            }.onFailure { error ->
                NPLogger.e(TAG, "记录元信息回滚失败状态失败，保留恢复文件", error)
            }
            activeRecordIds -= record.id
            recoveryCompleted = false
        }
        return false
    }

    suspend fun recoverInterruptedWrites(context: Context): Int = withContext(Dispatchers.IO) {
        recoveryMutex.withLock {
            if (recoveryCompleted) return@withLock 0
            val directory = stagingDirectory(context)
            val journals = directory.listFiles { file ->
                file.isFile && file.name.startsWith(METADATA_RECOVERY_PREFIX) &&
                    file.name.endsWith(METADATA_RECOVERY_SUFFIX)
            }.orEmpty()
            var recoveredCount = 0
            var allResolved = true
            journals.forEach { journal ->
                val record = runCatching { readRecord(context, journal) }
                    .onFailure { error ->
                        NPLogger.e(TAG, "读取元信息恢复凭据失败，原文件和备份均保留: ${journal.name}", error)
                    }
                    .getOrNull() ?: run {
                    allResolved = false
                    return@forEach
                }
                if (record.id in activeRecordIds) {
                    allResolved = false
                    return@forEach
                }
                val recovered = recoverRecord(context, record)
                if (recovered) {
                    recoveredCount++
                } else {
                    allResolved = false
                }
            }
            recoveryCompleted = allResolved
            recoveredCount
        }
    }

    internal fun resetRecoveryForTest() {
        recoveryCompleted = false
        activeRecordIds.clear()
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
        }
        ManagedDownloadAtomicFile.writeTextAtomically(record.journalFile, body.toString())
    }

    private fun readRecord(
        context: Context,
        journalFile: File
    ): LocalMetadataRecoveryRecord {
        val body = JSONObject(journalFile.readText())
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
            journalFile = journalFile
        )
    }

    private fun cleanup(record: LocalMetadataRecoveryRecord): Boolean {
        activeRecordIds -= record.id
        val recoveryFilesRemoved = listOf(record.backupFile, record.updatedFile).all { file ->
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
