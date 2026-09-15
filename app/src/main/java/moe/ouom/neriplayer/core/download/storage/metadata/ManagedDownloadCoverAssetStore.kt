package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageReadLimitExceededException
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.readBounded

internal object ManagedDownloadCoverAssetStore {
    data class MaterializedCover(
        val reference: String,
        val assetHash: String,
        val fileName: String? = null
    )

    suspend fun inspect(
        context: Context,
        reference: String?
    ): MaterializedCover? = withContext(Dispatchers.IO) {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
        val source = readCover(context, normalized) ?: return@withContext null
        try {
            MaterializedCover(
                reference = normalized,
                assetHash = source.assetHash,
                fileName = source.displayName
            )
        } finally {
            source.delete()
        }
    }

    suspend fun materialize(
        context: Context,
        reference: String?,
        preferredFileName: String? = null,
        extension: String = "jpg",
        mimeType: String? = "image/jpeg"
    ): MaterializedCover? = withContext(Dispatchers.IO) {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
        val source = readCover(context, normalized) ?: return@withContext null
        try {
            val hash = source.assetHash
            if (ManagedDownloadStorage.isManagedCoverReference(context, normalized)) {
                return@withContext MaterializedCover(
                    reference = normalized,
                    assetHash = hash,
                    fileName = source.displayName
                )
            }
            val fileName = selectTargetFileName(
                sourceDisplayName = source.displayName,
                preferredFileName = preferredFileName
            ) ?: buildLegacyReadableFileName(
                sourceDisplayName = source.displayName,
                assetHash = hash,
                extension = extension
            )
            val stored = source.file.inputStream().use { input ->
                ManagedDownloadStorage.persistRemoteCoverStream(
                    context = context,
                    input = input,
                    fileName = fileName,
                    mimeType = mimeType,
                    expectedSizeBytes = source.sizeBytes
                )
            } ?: return@withContext null
            MaterializedCover(
                reference = stored,
                assetHash = hash,
                fileName = fileName
            )
        } finally {
            source.delete()
        }
    }

    /** legacy imports keep a readable source name and use only a short hash for collisions */
    suspend fun materializeLegacyReadable(
        context: Context,
        reference: String?,
        extension: String = "jpg",
        mimeType: String? = "image/jpeg"
    ): MaterializedCover? = withContext(Dispatchers.IO) {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
        val source = readCover(context, normalized) ?: return@withContext null
        try {
            val hash = source.assetHash
            if (ManagedDownloadStorage.isManagedCoverReference(context, normalized)) {
                return@withContext MaterializedCover(
                    reference = normalized,
                    assetHash = hash,
                    fileName = source.displayName
                )
            }
            val fileName = selectTargetFileName(
                sourceDisplayName = source.displayName,
                preferredFileName = buildLegacyReadableFileName(
                    sourceDisplayName = source.displayName,
                    assetHash = hash,
                    extension = extension
                )
            ) ?: buildLegacyReadableFileName(
                sourceDisplayName = source.displayName,
                assetHash = hash,
                extension = extension
            )
            val stored = source.file.inputStream().use { input ->
                ManagedDownloadStorage.persistRemoteCoverStream(
                    context = context,
                    input = input,
                    fileName = fileName,
                    mimeType = mimeType,
                    expectedSizeBytes = source.sizeBytes
                )
            } ?: return@withContext null
            MaterializedCover(
                reference = stored,
                assetHash = hash,
                fileName = fileName
            )
        } finally {
            source.delete()
        }
    }

    internal fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun selectTargetFileName(
        sourceDisplayName: String?,
        preferredFileName: String?
    ): String? {
        val requestedName = preferredFileName?.let(::normalizePreferredFileName)
        return requestedName?.takeUnless { requested ->
            requested.equals(sourceDisplayName, ignoreCase = true)
        }
    }

    internal fun buildLegacyReadableFileName(
        sourceDisplayName: String,
        assetHash: String,
        extension: String
    ): String {
        val normalizedExtension = extension.trim().removePrefix(".").ifBlank { "bin" }
        val sourceBaseName = sourceDisplayName.substringBeforeLast('.', sourceDisplayName)
        val readableBaseName = sourceBaseName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .trimEnd('.')
            .takeUnless { it.matches(Regex("[0-9a-fA-F]{32,64}")) }
            .orEmpty()
            .ifBlank { "cover" }
        val shortHash = assetHash.take(8)
        val targetBaseName = if (readableBaseName.endsWith("-$shortHash", ignoreCase = true)) {
            readableBaseName
        } else {
            "$readableBaseName-$shortHash"
        }
        return "$targetBaseName.$normalizedExtension"
    }

    private fun normalizePreferredFileName(fileName: String): String {
        val normalized = fileName.trim()
        require(
            normalized.isNotBlank() &&
                normalized != "." &&
                normalized != ".." &&
                '/' !in normalized &&
                '\\' !in normalized
        ) {
            "preferred cover file name must be a single valid path segment"
        }
        return normalized
    }

    private suspend fun readCover(context: Context, reference: String): ReadCover? {
        val target = resolveReference(context, reference) ?: return null
        val displayName = when (val result = target.backend.stat(target.reference)) {
            is StorageLookupResult.Found -> {
                result.value.sizeBytes
                    ?.takeIf { size -> size > MAX_SOURCE_COVER_BYTES }
                    ?.let { size ->
                        throw CoverSourceTooLargeException(
                            actualBytes = size,
                            maxBytes = MAX_SOURCE_COVER_BYTES
                        )
                    }
                result.value.displayName
            }
            StorageLookupResult.Missing -> return null
            StorageLookupResult.PermissionLost -> {
                throw SecurityException("cover storage permission lost: $reference")
            }
            is StorageLookupResult.ProviderFailure -> throw result.error
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> return null
        }
        val spool = createSpoolFile(context)
        val result = try {
            target.backend.readBounded(
                reference = target.reference,
                maxBytes = MAX_SOURCE_COVER_BYTES
            ) { input ->
                spool.outputStream().use { output ->
                    val buffer = ByteArray(COVER_STREAM_BUFFER_SIZE_BYTES)
                    var total = 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read.toLong()
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.flush()
                    total to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                }
            }
        } catch (error: CancellationException) {
            spool.delete()
            throw error
        } catch (error: Throwable) {
            spool.delete()
            throw error
        }
        return when (result) {
            is StorageLookupResult.Found -> {
                val (sizeBytes, assetHash) = result.value
                if (sizeBytes <= 0L) {
                    spool.delete()
                    null
                } else {
                    try {
                        validatePixelBudgetOrThrow(spool)
                        ReadCover(
                            file = spool,
                            sizeBytes = sizeBytes,
                            assetHash = assetHash,
                            displayName = displayName
                        )
                    } catch (error: Throwable) {
                        spool.delete()
                        throw error
                    }
                }
            }
            StorageLookupResult.Missing -> {
                spool.delete()
                null
            }
            StorageLookupResult.PermissionLost -> {
                spool.delete()
                throw SecurityException("cover storage permission lost: $reference")
            }
            is StorageLookupResult.ProviderFailure -> {
                spool.delete()
                val error = result.error
                if (error is StorageReadLimitExceededException) {
                    throw CoverSourceTooLargeException(
                        actualBytes = error.actualBytes,
                        maxBytes = error.maxBytes
                    )
                }
                throw error
            }
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> {
                spool.delete()
                null
            }
        }
    }

    private data class ReadCover(
        val file: File,
        val sizeBytes: Long,
        val assetHash: String,
        val displayName: String
    ) {
        fun delete() {
            if (file.exists() && !file.delete()) {
                // 临时文件只保存 source 副本，删除失败不影响 durable audio
                file.deleteOnExit()
            }
        }
    }

    private fun createSpoolFile(context: Context): File {
        val preferredDirectory = runCatching { context.cacheDir }
            .getOrNull()
            ?.takeIf { directory -> directory.isDirectory || directory.mkdirs() }
        val directory = preferredDirectory
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("无法创建封面临时目录: ${directory.absolutePath}")
        }
        return File.createTempFile(".neriplayer-cover-", ".tmp", directory)
    }

    private fun validatePixelBudgetOrThrow(file: File) {
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        }.getOrNull() ?: return
        // 非图片或 Provider 无法提供 bounds 的旧 sidecar 留给下游格式校验
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        if (!isCoverPixelBudgetWithin(bounds.outWidth, bounds.outHeight)) {
            throw CoverPixelBudgetExceededException(bounds.outWidth, bounds.outHeight)
        }
    }

    private fun resolveReference(context: Context, rawReference: String): ResolvedReference? {
        val uri = runCatching { rawReference.toUri() }.getOrNull()
        if (uri?.scheme?.equals("content", ignoreCase = true) == true) {
            return ResolvedReference(
                backend = SafStorageBackend(context),
                reference = StorageReference.SafRef(uri)
            )
        }
        val file = when {
            rawReference.startsWith("/") -> File(rawReference)
            rawReference.startsWith("file:", ignoreCase = true) -> {
                // java.net.URI preserves the decoded local path for file:/ and file:/// forms
                runCatching { File(URI(rawReference)) }.getOrNull()
            }
            uri?.scheme.isNullOrBlank() -> File(rawReference)
            else -> null
        } ?: return null
        return file.parentFile?.let { parent ->
            ResolvedReference(
                backend = FileStorageBackend(parent),
                reference = StorageReference.FileRef(file.name)
            )
        }
    }

    private data class ResolvedReference(
        val backend: moe.ouom.neriplayer.core.download.storage.backend.StorageBackend,
        val reference: StorageReference
    )
}
