package moe.ouom.neriplayer.api.sync.github

import java.security.MessageDigest
import moe.ouom.neriplayer.api.sync.http.SyncFileTransferLimits

internal object GitHubArchiveUploadValidation {
    private const val MANIFEST_PATH = "neriplayer-sync-v3.manifest"
    private val objectPath = Regex("neriplayer-sync-v[34]-([0-9a-f]{64})\\.zst")

    fun validate(path: String, content: ByteArray) {
        validateSize(content.size)
        if (path == MANIFEST_PATH) return
        val expectedHash = objectPath.matchEntire(path)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid sync content address")
        require(sha256(content) == expectedHash) { "Sync object does not match its content address" }
    }

    private fun validateSize(size: Int) {
        require(size > 0) { "Refusing to upload an empty sync object" }
        require(size <= SyncFileTransferLimits.ARCHIVE_FILE_BYTES) { "Sync object exceeds upload budget" }
    }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it) }
}
