package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference

internal object ManagedDownloadCoverAssetStore {
    private const val MAX_COVER_BYTES = 16L * 1024L * 1024L

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
        if (source.bytes.isEmpty()) return@withContext null
        MaterializedCover(
            reference = normalized,
            assetHash = sha256(source.bytes),
            fileName = source.displayName
        )
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
        if (source.bytes.isEmpty()) return@withContext null
        val hash = sha256(source.bytes)
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
        val stored = try {
            ManagedDownloadStorage.persistRemoteCoverBytes(
                context = context,
                bytes = source.bytes,
                fileName = fileName,
                mimeType = mimeType
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw error
        } ?: return@withContext null
        MaterializedCover(
            reference = stored,
            assetHash = hash,
            fileName = fileName
        )
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
        if (source.bytes.isEmpty()) return@withContext null
        val hash = sha256(source.bytes)
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
        val stored = ManagedDownloadStorage.persistRemoteCoverBytes(
            context = context,
            bytes = source.bytes,
            fileName = fileName,
            mimeType = mimeType
        ) ?: return@withContext null
        MaterializedCover(
            reference = stored,
            assetHash = hash,
            fileName = fileName
        )
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
            is StorageLookupResult.Found -> result.value.displayName
            StorageLookupResult.Missing -> return null
            StorageLookupResult.PermissionLost -> {
                throw SecurityException("cover storage permission lost: $reference")
            }
            is StorageLookupResult.ProviderFailure -> throw result.error
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> return null
        }
        val result = target.backend.read(target.reference) { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_COVER_BYTES) {
                    throw IllegalArgumentException("cover exceeds maximum size")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return when (result) {
            is StorageLookupResult.Found -> ReadCover(
                bytes = result.value,
                displayName = displayName
            )
            StorageLookupResult.Missing -> null
            StorageLookupResult.PermissionLost -> {
                throw SecurityException("cover storage permission lost: $reference")
            }
            is StorageLookupResult.ProviderFailure -> throw result.error
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> null
        }
    }

    private data class ReadCover(
        val bytes: ByteArray,
        val displayName: String
    )

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
