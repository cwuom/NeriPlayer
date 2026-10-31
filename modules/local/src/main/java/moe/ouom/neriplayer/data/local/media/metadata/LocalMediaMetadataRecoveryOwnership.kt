package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import moe.ouom.neriplayer.common.logging.NPLogger

internal fun verifiedMetadataTargetIdentity(
    context: Context,
    reference: String,
    originalSha256: String,
    writable: Boolean = false
): String? =
    runCatching {
        val file = LocalMediaMetadataRecoveryStore.directFile(reference)
        if (file != null) {
            val before = metadataFileIdentity(file) ?: return@runCatching null
            before.takeIf {
                LocalMediaMetadataRecoveryStore.targetMatches(context, reference, originalSha256) &&
                    metadataFileIdentity(file) == before
            }
        } else {
            context.contentResolver.openFileDescriptor(reference.toUri(), if (writable) "rw" else "r")?.use { descriptor ->
                val identity = metadataDescriptorIdentity(descriptor)
                identity.takeIf {
                    withMetadataDescriptorInput(descriptor) { input ->
                        LocalMediaMetadataRecoveryStore.sha256(input) == originalSha256
                    }
                }
            }
        }
    }.onFailure { error ->
        NPLogger.w("LocalMetadataRecovery", "读取元信息恢复文件身份失败，保留原内容", error)
    }.getOrNull()

internal fun canRecoverMetadataTarget(context: Context, record: LocalMetadataRecoveryRecord): Boolean = runCatching {
    val file = LocalMediaMetadataRecoveryStore.directFile(record.targetReference)
    if (file == null) {
        // 读取视图可能是管道，已有写入身份时用非截断的写入视图验证恢复许可
        val mode = if (record.originalWritableFileIdentity != null) "rw" else "r"
        return@runCatching context.contentResolver.openFileDescriptor(record.targetReference.toUri(), mode)
            ?.use { canRecoverMetadataDescriptor(it, record) } == true
    }
    val identity = record.originalFileIdentity ?: return@runCatching false
    if (metadataFileIdentity(file) != identity) return@runCatching false
    // 同一个实例也可能被外部原地修改，拒绝不符合已校验写入前缀的内容
    (LocalMediaMetadataRecoveryStore.targetMatches(context, record.targetReference, record.originalSha256) ||
        LocalMediaMetadataRecoveryStore.targetMatches(context, record.targetReference, record.updatedSha256) ||
        interruptedMetadataSources(record).any { source -> file.inputStream().use { matchesMetadataPrefix(it, source) } }) &&
        metadataFileIdentity(file) == identity
}.getOrDefault(false)

internal fun canRecoverMetadataDescriptor(descriptor: ParcelFileDescriptor, record: LocalMetadataRecoveryRecord): Boolean =
    runCatching {
        if (!metadataDescriptorMatchesRecordedIdentity(descriptor, record)) return@runCatching false
        val digest = metadataDescriptorSha256(descriptor)
        if (digest == record.originalSha256 || digest == record.updatedSha256) return@runCatching true
        interruptedMetadataSources(record).any { source ->
            withMetadataDescriptorInput(descriptor) { matchesMetadataPrefix(it, source) }
        }
    }.getOrDefault(false)

internal fun metadataDescriptorMatchesRecordedIdentity(
    descriptor: ParcelFileDescriptor,
    record: LocalMetadataRecoveryRecord
): Boolean {
    val recordedIdentities = listOfNotNull(record.originalFileIdentity, record.originalWritableFileIdentity)
    if (recordedIdentities.isEmpty()) return false
    val stat = Os.fstat(descriptor.fileDescriptor)
    check(OsConstants.S_ISREG(stat.st_mode)) { "元信息恢复目标缺少稳定文件身份" }
    val identity = "${stat.st_dev}:${stat.st_ino}"
    val legacyIdentity = if (LocalMediaMetadataRecoveryStore.directFile(record.targetReference) != null) {
        "file:(dev=${java.lang.Long.toHexString(stat.st_dev)},ino=${stat.st_ino})"
    } else null
    return recordedIdentities.any { it == identity || it == legacyIdentity }
}

internal fun metadataDescriptorSha256(descriptor: ParcelFileDescriptor): String =
    withMetadataDescriptorInput(descriptor, LocalMediaMetadataRecoveryStore::sha256)

private fun interruptedMetadataSources(record: LocalMetadataRecoveryRecord): List<File> =
    listOf(record.updatedFile to record.updatedSha256, record.backupFile to record.originalSha256)
        .filter { (source, digest) ->
            source.isFile && runCatching { LocalMediaMetadataRecoveryStore.sha256(source) == digest }.getOrDefault(false)
        }
        .map { it.first }

private fun metadataFileIdentity(file: File): String? =
    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()?.let { "file:$it" }

private fun metadataDescriptorIdentity(descriptor: ParcelFileDescriptor): String = Os.fstat(descriptor.fileDescriptor).let { stat ->
    check(OsConstants.S_ISREG(stat.st_mode)) { "元信息恢复目标缺少稳定文件身份" }
    "${stat.st_dev}:${stat.st_ino}"
}

private fun <T> withMetadataDescriptorInput(descriptor: ParcelFileDescriptor, read: (InputStream) -> T): T {
    Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
    try {
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use(read)
    } finally {
        // dup 共享文件偏移，检查之后必须让写入仍从文件开头开始
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
    }
}

private fun matchesMetadataPrefix(actual: InputStream, source: File): Boolean = try {
    source.inputStream().use { expected ->
        val expectedInput = DataInputStream(expected)
        val actualBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val expectedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = actual.read(actualBuffer)
        while (count >= 0) {
            expectedInput.readFully(expectedBuffer, 0, count)
            if ((0 until count).any { actualBuffer[it] != expectedBuffer[it] }) return@use false
            count = actual.read(actualBuffer)
        }
        true
    }
} catch (_: EOFException) {
    false
}
