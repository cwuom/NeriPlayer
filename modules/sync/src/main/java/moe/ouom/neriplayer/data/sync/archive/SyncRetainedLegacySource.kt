package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

internal class SyncRetainedLegacySource(directory: File) {
    private val file = File(directory, "legacy-source.raw")
    private var sourceHash: String? = null
    var checksum: String? = null
        private set

    fun invalidate() {
        sourceHash = null
        checksum = null
    }

    fun resolve(source: SyncLegacyLyricSource?): File? {
        if (source == null) return null
        if (source.hash != sourceHash) return null
        return file.takeIf { it.isFile && it.length() == source.rawDataBytes }
    }

    fun retain(source: SyncLegacyLyricSource?, original: File?) {
        if (source == null) {
            file.delete()
            invalidate()
            return
        }
        val input = requireNotNull(original)
        require(input.length() == source.rawDataBytes) { "Invalid retained legacy lyric size" }
        checksum = copyAtomic(input)
        sourceHash = source.hash
    }

    private fun copyAtomic(input: File): String {
        val temporary = File.createTempFile("legacy-", ".tmp", file.parentFile)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temporary).use { output ->
                DigestInputStream(input.inputStream(), digest).use { it.copyTo(output) }
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Unable to retain legacy lyric source" }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            temporary.delete()
        }
    }
}
