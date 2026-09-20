package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.provider.DocumentsContract
import android.system.Os
import androidx.core.net.toUri
import java.io.File
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
    val deferredDelete: Boolean = false
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
            return LocalMediaCompanionRecoveryEntry(
                reference, backup, original, text("expectedSha256"),
                body.optLong("originalLastModifiedMs").takeIf { it > 0L }, created,
                text("fileIdentity"), body.optBoolean("writeIdentityVerified"),
                body.optBoolean("deferredDelete")
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
        track(reference, ByteArray(0), created = true)
    }

    fun beforeWrite(reference: String, bytes: ByteArray, created: Boolean = false) {
        track(reference, bytes, created)
    }

    fun afterWrite(reference: String) {
        val current = requireNotNull(record)
        val entry = current.companions.single { it.reference == reference }
        if (!companionMatches(context, reference, entry.expectedSha256)) throw IOException("伴随文件写入读回失败: $reference")
        val identity = companionFile(reference)?.let(::companionFileIdentity)
        save(current.copy(companions = current.companions.map {
            if (it.reference == reference) {
                it.copy(fileIdentity = identity, writeIdentityVerified = true)
            } else {
                it
            }
        }))
    }

    fun deferDelete(reference: String) {
        track(reference, null, created = false, deferredDelete = true)
    }

    private fun track(reference: String, bytes: ByteArray?, created: Boolean, deferredDelete: Boolean = false) {
        initializeSidecarsOnly()
        val current = requireNotNull(record)
        val existing = current.companions.firstOrNull { it.reference == reference }
        if (existing != null) {
            val currentIdentity = companionFile(reference)
                ?.takeIf(File::isFile)
                ?.let(::companionFileIdentity)
            save(current.copy(companions = current.companions.map {
                if (it.reference == reference) {
                    it.copy(
                        expectedSha256 = bytes?.let(::companionDigest),
                        fileIdentity = currentIdentity ?: it.fileIdentity,
                        writeIdentityVerified = false,
                        deferredDelete = deferredDelete
                    )
                } else {
                    it
                }
            }))
            return
        }
        val file = companionFile(reference)
        if (file?.exists() == true && !file.isFile) throw IOException("伴随目标不是文件: $reference")
        val isNew = created || file != null && !file.exists()
        val original = if (isNew) null else companionBytes(context, reference)
            ?: throw IOException("伴随原文件不可读: $reference")
        val backup = original?.let { content ->
            File.createTempFile("companion-backup-", ".bin", current.journalFile.parentFile).also {
                FileOutputStream(it).use { output -> output.write(content); output.fd.sync() }
            }
        }
        val entry = LocalMediaCompanionRecoveryEntry(reference, backup, original?.let(::companionDigest),
            bytes?.let(::companionDigest), file?.lastModified()?.takeIf { it > 0L }, isNew,
            file?.takeIf(File::isFile)?.let(::companionFileIdentity), deferredDelete = deferredDelete)
        save(current.copy(companions = current.companions + entry))
    }

    private fun save(updated: LocalMetadataRecoveryRecord) {
        record = LocalMediaMetadataRecoveryStore.saveCompanionRecord(updated)
    }

    fun commit() {
        val current = requireNotNull(record)
        if (!current.audioUnchanged && !companionMatches(context, audioReference, current.updatedSha256)) {
            throw IOException("伴随事务音频完整性未确认")
        }
        if (current.companions.any { !it.deferredDelete && !companionMatches(context, it.reference, it.expectedSha256) }) {
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
        val restored = LocalMediaMetadataRecoveryStore.rollback(context, current)
        if (restored) record = null
        return restored
    }
}

internal fun rollbackLocalMediaCompanions(context: Context, record: LocalMetadataRecoveryRecord): Boolean {
    var restored = true
    record.companions.asReversed().forEach { entry ->
        val entryRestored = runCatching {
            if (entry.deferredDelete) return@runCatching true
            if (entry.createdByTransaction) {
                if (companionMissing(context, entry.reference)) return@runCatching true
                if (!companionMatches(context, entry.reference, entry.expectedSha256)) return@runCatching false
                val file = companionFile(entry.reference)
                if (file != null && (entry.fileIdentity == null || companionFileIdentity(file) != entry.fileIdentity)) return@runCatching false
                return@runCatching deleteCompanion(context, entry.reference)
            }
            if (companionMatches(context, entry.reference, entry.originalSha256)) return@runCatching true
            if (!companionMatches(context, entry.reference, entry.expectedSha256)) return@runCatching false
            val currentFile = companionFile(entry.reference)
            if (
                currentFile != null &&
                    entry.writeIdentityVerified &&
                    (entry.fileIdentity == null || companionFileIdentity(currentFile) != entry.fileIdentity)
            ) {
                return@runCatching false
            }
            val backup = entry.backupFile ?: return@runCatching false
            val bytes = backup.readBytes()
            if (companionDigest(bytes) != entry.originalSha256) return@runCatching false
            val restored = if (currentFile != null) LocalMediaSupport.writeBytesFileAtomically(currentFile, bytes)
                else LocalMediaSupport.writeBytesContent(context, entry.reference, bytes)
            restored && companionMatches(context, entry.reference, entry.originalSha256) &&
                (currentFile == null || entry.originalLastModifiedMs == null ||
                    currentFile.setLastModified(entry.originalLastModifiedMs))
        }.getOrDefault(false)
        if (!entryRestored) restored = false
    }
    return restored
}

internal fun finishLocalMediaCompanionDeletes(context: Context, record: LocalMetadataRecoveryRecord): Boolean {
    var deleted = true
    record.companions.filter { it.deferredDelete }.forEach { entry ->
        val entryDeleted = runCatching {
            if (companionMissing(context, entry.reference)) return@runCatching true
            if (!companionMatches(context, entry.reference, entry.originalSha256)) return@runCatching false
            val file = companionFile(entry.reference)
            if (file != null && entry.fileIdentity != null && companionFileIdentity(file) != entry.fileIdentity) {
                return@runCatching false
            }
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

private fun companionBytes(context: Context, reference: String): ByteArray? = companionFile(reference)?.readBytes()
    ?: context.contentResolver.openInputStream(reference.toUri())?.use { it.readBytes() }

private fun companionDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun companionMatches(context: Context, reference: String, digest: String?): Boolean = digest != null &&
    LocalMediaMetadataRecoveryStore.targetMatches(context, reference, digest)

private fun companionMissing(context: Context, reference: String): Boolean = companionFile(reference)?.let { !it.exists() }
    ?: (ManagedDownloadReferenceIo.inspect(context, reference) == ManagedDownloadReferenceIo.AccessResult.Missing)

private fun deleteCompanion(context: Context, reference: String): Boolean = companionFile(reference)?.delete()
    ?: DocumentsContract.deleteDocument(context.contentResolver, reference.toUri())
