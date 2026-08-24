package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
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
        val assetHash: String
    )

    suspend fun materialize(
        context: Context,
        reference: String?,
        extension: String = "jpg",
        mimeType: String? = "image/jpeg"
    ): MaterializedCover? = withContext(Dispatchers.IO) {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
        val bytes = readBytes(context, normalized) ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val hash = sha256(bytes)
        val fileName = "$hash.${extension.trim().removePrefix(".").ifBlank { "bin" }}"
        val stored = try {
            ManagedDownloadStorage.persistRemoteCoverBytes(
                context = context,
                bytes = bytes,
                fileName = fileName,
                mimeType = mimeType
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw error
        } ?: return@withContext null
        MaterializedCover(reference = stored, assetHash = hash)
    }

    internal fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun readBytes(context: Context, reference: String): ByteArray? {
        val target = resolveReference(context, reference) ?: return null
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
            is StorageLookupResult.Found -> result.value
            StorageLookupResult.Missing -> null
            StorageLookupResult.PermissionLost -> {
                throw SecurityException("cover storage permission lost: $reference")
            }
            is StorageLookupResult.ProviderFailure -> throw result.error
            StorageLookupResult.OutOfScope,
            is StorageLookupResult.Unsupported -> null
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
        val path = when {
            rawReference.startsWith("/") -> rawReference
            uri?.scheme?.equals("file", ignoreCase = true) == true -> uri.path
            else -> rawReference
        }?.takeIf(String::isNotBlank) ?: return null
        val file = File(path)
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
