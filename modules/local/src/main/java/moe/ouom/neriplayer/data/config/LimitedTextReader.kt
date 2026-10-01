package moe.ouom.neriplayer.data.config

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

internal object LimitedTextReader {
    private const val BUFFER_SIZE = 8 * 1024

    fun readUtf8(context: Context, uri: Uri, maxBytes: Long): String {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open input")
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read.toLong()
                if (total > maxBytes) {
                    throw IOException("Input file is too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
