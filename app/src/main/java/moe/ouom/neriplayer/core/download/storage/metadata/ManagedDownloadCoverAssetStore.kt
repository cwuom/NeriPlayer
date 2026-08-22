package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

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
        val stored = runCatching {
            ManagedDownloadStorage.persistRemoteCoverBytes(
                context = context,
                bytes = bytes,
                fileName = fileName,
                mimeType = mimeType
            )
        }.getOrNull() ?: return@withContext null
        MaterializedCover(reference = stored, assetHash = hash)
    }

    internal fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun readBytes(context: Context, reference: String): ByteArray? {
        return runCatching {
            val stream = if (reference.startsWith("/")) {
                File(reference).inputStream()
            } else {
                val uri = Uri.parse(reference)
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    uri.path?.let(::File)?.inputStream()
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            } ?: return null
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }
}
