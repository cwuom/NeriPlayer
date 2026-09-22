package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.provider.DocumentsContract
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.system.Os
import androidx.core.net.toUri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.json.JSONObject

internal data class LocalMediaCompanionRecoveryEntry(
    val reference: String,
    val backupFile: File?,
    val originalSha256: String?,
    val expectedSha256: String?,
    val originalLastModifiedMs: Long?,
    val createdByTransaction: Boolean,
    val fileIdentity: String? = null,
    val writeIdentityVerified: Boolean = false,
    val deferredDelete: Boolean = false,
    val intendedFile: File? = null,
    val phase: String? = null,
    val previousSha256: String? = null,
    val previousIdentity: String? = null,
    val stagedFile: File? = null,
    val restoreInputSha256: String? = null,
    val stagedIdentity: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("reference", reference)
        put("backupPath", backupFile?.absolutePath ?: JSONObject.NULL)
        put("originalSha256", originalSha256 ?: JSONObject.NULL)
        put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
        put("originalLastModifiedMs", originalLastModifiedMs ?: JSONObject.NULL)
        put("createdByTransaction", createdByTransaction)
        put("fileIdentity", fileIdentity ?: JSONObject.NULL)
        put("writeIdentityVerified", writeIdentityVerified)
        put("deferredDelete", deferredDelete)
        put("intendedPath", intendedFile?.absolutePath ?: JSONObject.NULL)
        put("phase", phase ?: JSONObject.NULL)
        put("previousSha256", previousSha256 ?: JSONObject.NULL)
        put("previousIdentity", previousIdentity ?: JSONObject.NULL)
        put("stagedPath", stagedFile?.absolutePath ?: JSONObject.NULL)
        put("restoreInputSha256", restoreInputSha256 ?: JSONObject.NULL)
        put("stagedIdentity", stagedIdentity ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(body: JSONObject, directory: File): LocalMediaCompanionRecoveryEntry {
            fun text(key: String) = body.optString(key).takeIf { body.has(key) && !body.isNull(key) && it.isNotBlank() }
            val backup = text("backupPath")?.let { path ->
                File(path).canonicalFile.also { require(it.parentFile == directory) }
            }
            val created = body.getBoolean("createdByTransaction")
            val original = text("originalSha256")
            require(created || backup != null && original?.length == 64)
            val reference = body.getString("reference")
            require(LocalMediaMetadataRecoveryStore.targetIdentity(reference) != null)
            require(text("phase") in setOf(null, "CREATED", "WRITE_INTENT", "ATOMIC_WRITE_INTENT", "WRITTEN", "RESTORING"))
            require(text("phase") != null || text("intendedPath") == null)
            return LocalMediaCompanionRecoveryEntry(
                reference, backup, original, text("expectedSha256"),
                body.optLong("originalLastModifiedMs").takeIf { it > 0L }, created,
                text("fileIdentity"), body.optBoolean("writeIdentityVerified"),
                body.optBoolean("deferredDelete"),
                text("intendedPath")?.let { File(it).canonicalFile.also { file -> require(file.parentFile == directory) } },
                text("phase"), text("previousSha256"), text("previousIdentity"),
                text("stagedPath")?.let { File(it).canonicalFile.also { file ->
                    val target = requireNotNull(companionFile(reference)).canonicalFile
                    require(file.parentFile == target.parentFile && file.name.startsWith(".${target.name}.companion-"))
                } },
                text("restoreInputSha256"), text("stagedIdentity")
            )
        }
    }
}

internal class LocalMediaCompanionTransaction(private val context: Context, private val audioReference: String) {
    var record: LocalMetadataRecoveryRecord? = null
        private set

    fun initialize(backup: File, updated: File, originalTime: Long?): LocalMetadataRecoveryRecord {
        check(record == null)
        return LocalMediaMetadataRecoveryStore.begin(context, audioReference, backup, updated, originalTime,
            companionTransaction = true).also { record = it }
    }

    fun initializeSidecarsOnly() {
        if (record != null) return
        val directory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { check(exists() || mkdirs()) }
        val backup = File.createTempFile("companion-source-", ".bin", directory)
        val updated = File.createTempFile("companion-updated-", ".bin", directory)
        record = LocalMediaMetadataRecoveryStore.begin(context, audioReference, backup, updated, null,
            companionTransaction = true, audioUnchanged = true)
    }

    fun prepareUpdatedAudio(): LocalMetadataRecoveryRecord {
        val updated = LocalMediaMetadataRecoveryStore.updatedCompanionAudio(requireNotNull(record))
        return LocalMediaMetadataRecoveryStore.markReplacing(updated).also { record = it }
    }

    fun audioVerified(): LocalMetadataRecoveryRecord =
        LocalMediaMetadataRecoveryStore.markTargetVerified(requireNotNull(record)).also { record = it }

    fun created(reference: String) {
        try {
            track(reference, ByteArray(0), created = true, registerCreation = true)
        } catch (error: Exception) {
            val current = record ?: throw error
            if (current.companions.none { it.reference == reference }) {
                // Provider 已创建对象但无法证明身份时，保留引用等待处理，不能猜测删除
                val intended = durableCompanionFile(current, "companion-intended-", ByteArray(0))
                val unresolved = LocalMediaCompanionRecoveryEntry(
                    reference, null, null, companionDigest(ByteArray(0)), null, true,
                    intendedFile = intended, phase = "WRITE_INTENT"
                )
                save(current.copy(companions = current.companions + unresolved))
            }
            throw error
        }
    }

    fun beforeWrite(reference: String, bytes: ByteArray, created: Boolean = false) {
        track(reference, bytes, created)
    }

    fun afterWrite(reference: String) {
        val current = requireNotNull(record)
        val entry = current.companions.single { it.reference == reference }
        val snapshot = companionSnapshot(context, reference)
        if (companionDigest(snapshot.bytes) != entry.expectedSha256 || snapshot.identity != entry.fileIdentity) {
            throw IOException("伴随文件写入读回或身份校验失败: $reference")
        }
        save(current.copy(companions = current.companions.map {
            if (it.reference == reference) it.copy(writeIdentityVerified = true, phase = "WRITTEN") else it
        }))
    }

    fun write(reference: String, bytes: ByteArray, created: Boolean = false): Boolean {
        beforeWrite(reference, bytes, created)
        val target = companionFile(reference)
        if (target == null) {
            val entry = requireNotNull(record).companions.single { it.reference == reference }
            writeRegularCompanion(context, entry.reference, bytes, requireNotNull(entry.fileIdentity))
        } else {
            publishPreparedFile(reference)
        }
        afterWrite(reference)
        return true
    }

    internal fun publishPreparedFile(reference: String) {
        val current = requireNotNull(record)
        val entry = current.companions.single { it.reference == reference }
        val target = requireNotNull(companionFile(reference))
        val parent = requireNotNull(target.parentFile).apply { check(exists() || mkdirs()) }
        val staged = File.createTempFile(".${target.name}.companion-", ".tmp", parent)
        val intended = requireNotNull(entry.intendedFile).readBytes()
        require(companionDigest(intended) == entry.expectedSha256)
        try {
            FileOutputStream(staged).use { it.write(intended); it.fd.sync() }
            val identity = companionFileIdentity(staged)
            val prepared = entry.copy(stagedFile = staged, stagedIdentity = identity, fileIdentity = identity, phase = "ATOMIC_WRITE_INTENT")
            save(current.copy(companions = current.companions.map { if (it.reference == reference) prepared else it }))
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
        if (target.exists()) {
            val actual = companionSnapshot(context, reference)
            check(actual.identity == entry.previousIdentity && companionDigest(actual.bytes) == entry.previousSha256)
        } else check(entry.createdByTransaction)
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun deferDelete(reference: String) {
        track(reference, null, created = false, deferredDelete = true)
    }

    private fun track(
        reference: String,
        bytes: ByteArray?,
        created: Boolean,
        deferredDelete: Boolean = false,
        registerCreation: Boolean = false
    ) {
        initializeSidecarsOnly()
        val current = requireNotNull(record)
        val existing = current.companions.firstOrNull { it.reference == reference }
        val file = companionFile(reference)
        if (file?.exists() == true && !file.isFile) throw IOException("伴随目标不是文件")
        val isNew = existing?.createdByTransaction ?: (created || file != null && !file.exists())
        val snapshot = if (file != null && !file.exists()) null else companionSnapshot(context, reference)
        if (existing != null && snapshot != null) {
            check(existing.fileIdentity == snapshot.identity) { "伴随文件对象已替换" }
            val digest = companionDigest(snapshot.bytes)
            check(digest == existing.expectedSha256 || digest == existing.originalSha256) { "伴随文件内容冲突" }
        }
        if (snapshot != null) {
            val verified = companionSnapshot(context, reference)
            check(verified.identity == snapshot.identity && verified.bytes.contentEquals(snapshot.bytes)) { "伴随文件映射不稳定" }
        }
        // 重复的创建通知不能重新激活已经消费的创建基线
        if (registerCreation && existing != null) return
        val intendedBytes = if (registerCreation) requireNotNull(snapshot).bytes else bytes
        val original = if (isNew) null else snapshot?.bytes ?: throw IOException("伴随原文件不可读")
        val backup = existing?.backupFile ?: original?.let { content ->
            durableCompanionFile(current, "companion-backup-", content)
        }
        if (deferredDelete && existing != null) {
            save(current.copy(companions = current.companions.map {
                if (it.reference == reference) it.copy(deferredDelete = true) else it
            }))
            return
        }
        val intended = intendedBytes?.let { durableCompanionFile(current, "companion-intended-", it) }
        val entry = LocalMediaCompanionRecoveryEntry(
            reference, backup, existing?.originalSha256 ?: original?.let(::companionDigest),
            intendedBytes?.let(::companionDigest), existing?.originalLastModifiedMs ?: file?.lastModified()?.takeIf { it > 0L }, isNew,
            snapshot?.identity, deferredDelete = deferredDelete,
            intendedFile = intended,
            phase = when {
                registerCreation -> "CREATED"
                bytes != null -> "WRITE_INTENT"
                else -> null
            },
            previousSha256 = snapshot?.bytes?.let(::companionDigest), previousIdentity = snapshot?.identity
        )
        try {
            save(current.copy(companions = current.companions.filterNot { it.reference == reference } + entry))
        } catch (error: Exception) {
            intended?.delete()
            if (existing == null) backup?.delete()
            throw error
        }
        existing?.intendedFile?.delete()
    }

    private fun save(updated: LocalMetadataRecoveryRecord) {
        record = LocalMediaMetadataRecoveryStore.saveCompanionRecord(updated)
    }

    fun commit() {
        val current = requireNotNull(record)
        if (!current.audioUnchanged && !companionMatches(context, audioReference, current.updatedSha256)) {
            throw IOException("伴随事务音频完整性未确认")
        }
        if (current.companions.any { entry ->
                !entry.deferredDelete && companionSnapshot(context, entry.reference).let { snapshot ->
                    companionDigest(snapshot.bytes) != entry.expectedSha256 || snapshot.identity != entry.fileIdentity
                }
            }) {
            throw IOException("伴随事务内容未完整读回")
        }
        val committed = current.copy(companionCommitted = true, stage = LocalMetadataRecoveryStage.TARGET_VERIFIED)
        save(committed)
        if (!finishLocalMediaCompanionDeletes(context, committed)) {
            LocalMediaMetadataRecoveryStore.releaseCompanionRecord(committed)
            return
        }
        LocalMediaMetadataRecoveryStore.complete(committed)
    }

    fun rollback(): Boolean {
        val current = record ?: return true
        val restored = LocalMediaMetadataRecoveryStore.rollback(context, current) { record = it }
        if (restored) record = null
        return restored
    }
}

internal data class CompanionRollbackResult(val record: LocalMetadataRecoveryRecord, val restored: Boolean)

internal fun rollbackLocalMediaCompanions(context: Context, record: LocalMetadataRecoveryRecord): CompanionRollbackResult {
    var latest = record
    var restored = true
    record.companions.asReversed().forEach { entry ->
        val entryRestored = runCatching {
            if (entry.deferredDelete && entry.intendedFile == null) return@runCatching true
            if (companionMissing(context, entry.reference)) return@runCatching entry.createdByTransaction
            if (entry.phase == null) return@runCatching rollbackLegacyCompanion(context, entry)
            val current = companionSnapshot(context, entry.reference)
            val digest = companionDigest(current.bytes)
            if (!entry.createdByTransaction && digest == entry.originalSha256) return@runCatching true
            val backup = entry.backupFile?.readBytes()
            if (!entry.createdByTransaction && (backup == null || companionDigest(backup) != entry.originalSha256)) return@runCatching false
            val intended = entry.intendedFile?.readBytes()
            if (entry.phase != "RESTORING" && (intended == null || companionDigest(intended) != entry.expectedSha256)) return@runCatching false
            val sameObject = current.identity == entry.fileIdentity
            val matchesPrevious = current.identity == entry.previousIdentity && digest == entry.previousSha256
            val owned = when (entry.phase) {
                "CREATED", "WRITTEN" -> sameObject && digest == entry.expectedSha256
                "WRITE_INTENT" -> sameObject && bytePrefix(current.bytes, requireNotNull(intended)) || matchesPrevious
                "ATOMIC_WRITE_INTENT" -> sameObject && digest == entry.expectedSha256 || matchesPrevious
                "RESTORING" -> sameObject &&
                    (digest == entry.restoreInputSha256 || backup != null && bytePrefix(current.bytes, backup))
                else -> false
            }
            if (!owned) return@runCatching false
            if (entry.createdByTransaction) return@runCatching deleteCompanion(context, entry.reference)
            val restoring = if (entry.phase == "RESTORING") entry else
                entry.copy(phase = "RESTORING", fileIdentity = current.identity, restoreInputSha256 = digest)
            // 先持久记录恢复输入，恢复写入再次中断时只能接受这个输入或原备份前缀
            latest = LocalMediaMetadataRecoveryStore.saveCompanionRecord(latest.copy(companions = latest.companions.map {
                if (it.reference == entry.reference) restoring else it
            }))
            val target = companionFile(entry.reference)
            if (target != null) {
                if (!LocalMediaSupport.writeBytesFileAtomically(target, requireNotNull(backup))) return@runCatching false
            } else {
                writeRegularCompanion(context, entry.reference, requireNotNull(backup), current.identity)
            }
            companionMatches(context, entry.reference, entry.originalSha256) &&
                (target == null || entry.originalLastModifiedMs == null || target.setLastModified(entry.originalLastModifiedMs))
        }.getOrDefault(false)
        if (!entryRestored) restored = false
    }
    return CompanionRollbackResult(latest, restored)
}

private fun rollbackLegacyCompanion(context: Context, entry: LocalMediaCompanionRecoveryEntry): Boolean {
    if (!entry.createdByTransaction && companionMatches(context, entry.reference, entry.originalSha256)) return true
    if (!companionMatches(context, entry.reference, entry.expectedSha256)) return false
    val target = companionFile(entry.reference)
    if (target != null && (entry.createdByTransaction || entry.writeIdentityVerified) &&
        (entry.fileIdentity == null || companionFileIdentity(target) != entry.fileIdentity)) return false
    if (entry.createdByTransaction) return deleteCompanion(context, entry.reference)
    val backup = entry.backupFile?.readBytes() ?: return false
    if (companionDigest(backup) != entry.originalSha256) return false
    val written = if (target != null) LocalMediaSupport.writeBytesFileAtomically(target, backup)
        else LocalMediaSupport.writeBytesContent(context, entry.reference, backup)
    return written && companionMatches(context, entry.reference, entry.originalSha256) &&
        (target == null || entry.originalLastModifiedMs == null || target.setLastModified(entry.originalLastModifiedMs))
}

internal fun cleanupCompanionStagedFile(entry: LocalMediaCompanionRecoveryEntry): Boolean {
    val staged = entry.stagedFile ?: return true
    if (!staged.exists()) return true
    return runCatching { companionFileIdentity(staged) == entry.stagedIdentity && staged.delete() }.getOrDefault(false)
}

private fun durableCompanionFile(record: LocalMetadataRecoveryRecord, prefix: String, bytes: ByteArray): File =
    File.createTempFile(prefix, ".bin", record.journalFile.parentFile).also { file ->
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

private data class CompanionSnapshot(val identity: String, val bytes: ByteArray)

private fun companionSnapshot(context: Context, reference: String): CompanionSnapshot {
    val descriptor = companionFile(reference)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        ?: context.contentResolver.openFileDescriptor(reference.toUri(), "r") ?: throw IOException("伴随文件不可读")
    return descriptor.use { fd ->
        val stat = Os.fstat(fd.fileDescriptor)
        check(OsConstants.S_ISREG(stat.st_mode)) { "伴随文件不支持稳定普通文件身份" }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd.dup()).use { it.readBytes() }
        CompanionSnapshot("${stat.st_dev}:${stat.st_ino}", bytes)
    }
}

private fun bytePrefix(actual: ByteArray, expected: ByteArray): Boolean =
    actual.size <= expected.size && actual.indices.all { actual[it] == expected[it] }

private fun writeRegularCompanion(context: Context, reference: String, bytes: ByteArray, identity: String) {
    val descriptor = context.contentResolver.openFileDescriptor(reference.toUri(), "rwt")
        ?: throw IOException("伴随文件不可写")
    descriptor.use { fd ->
        val stat = Os.fstat(fd.fileDescriptor)
        check(OsConstants.S_ISREG(stat.st_mode) && "${stat.st_dev}:${stat.st_ino}" == identity) { "伴随写入对象已改变" }
        Os.ftruncate(fd.fileDescriptor, 0)
        ParcelFileDescriptor.AutoCloseOutputStream(fd.dup()).use { it.write(bytes); it.flush(); Os.fsync(fd.fileDescriptor) }
    }
}

internal fun finishLocalMediaCompanionDeletes(context: Context, record: LocalMetadataRecoveryRecord): Boolean {
    var deleted = true
    record.companions.filter { it.deferredDelete }.forEach { entry ->
        val entryDeleted = runCatching {
            if (companionMissing(context, entry.reference)) return@runCatching true
            val expected = entry.expectedSha256 ?: entry.originalSha256
            if (entry.fileIdentity != null) {
                val snapshot = companionSnapshot(context, entry.reference)
                if (snapshot.identity != entry.fileIdentity || companionDigest(snapshot.bytes) != expected) return@runCatching false
            } else if (!companionMatches(context, entry.reference, expected)) return@runCatching false
            deleteCompanion(context, entry.reference)
        }.getOrDefault(false)
        if (!entryDeleted) deleted = false
    }
    return deleted
}

private fun companionFile(reference: String): File? = when {
    reference.startsWith('/') -> File(reference)
    reference.startsWith("file:") -> reference.toUri().path?.let(::File)
    else -> null
}

private fun companionFileIdentity(file: File): String = Os.stat(file.absolutePath).let { "${it.st_dev}:${it.st_ino}" }

private fun companionDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun companionMatches(context: Context, reference: String, digest: String?): Boolean = digest != null &&
    LocalMediaMetadataRecoveryStore.targetMatches(context, reference, digest)

private fun companionMissing(context: Context, reference: String): Boolean = companionFile(reference)?.let { !it.exists() }
    ?: (ManagedDownloadReferenceIo.inspect(context, reference) == ManagedDownloadReferenceIo.AccessResult.Missing)

private fun deleteCompanion(context: Context, reference: String): Boolean = companionFile(reference)?.delete()
    ?: DocumentsContract.deleteDocument(context.contentResolver, reference.toUri())
