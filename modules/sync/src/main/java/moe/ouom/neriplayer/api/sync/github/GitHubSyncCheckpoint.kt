package moe.ouom.neriplayer.api.sync.github

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 检查点只保存已确认的 Git 对象标识，不保存令牌或歌曲正文 */
internal class GitHubSyncCheckpoint(
    root: File,
    namespace: String,
    private val nowMillis: () -> Long
) {
    private val directory = File(root, namespace)
    private val lock = locks[(directory.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]

    fun ensureReady() = synchronized(lock) {
        val state = readRateState() ?: return@synchronized
        if (state.retryAt > nowMillis()) throw rateException(state)
    }

    fun recordRateLimit(error: GitHubRateLimitException): GitHubRateLimitException = synchronized(lock) {
        val previous = readRateState()
        val attempts = ((previous?.attempts ?: 0) + 1).coerceAtMost(GitHubRateLimitPolicy.MAX_AUTOMATIC_RETRIES + 1)
        val backoff = GitHubRateLimitPolicy.MIN_RETRY_DELAY_MS * (1L shl (attempts - 1))
        val retryAt = maxOf(error.retryAtMillis, previous?.retryAt ?: 0L, GitHubRateLimitPolicy.addDelay(nowMillis(), backoff))
        val state = RateState(error.statusCode, retryAt, attempts)
        writeAtomically(File(directory, RATE_FILE), state.encode())
        rateException(state)
    }

    fun cachedBlob(content: ByteArray): String? = synchronized(lock) {
        val file = blobFile(content)
        val record = readSmallFile(file) ?: return@synchronized null
        val expected = gitBlobSha(content)
        if (record == "blob-v1\n$expected\n") return@synchronized expected
        file.delete()
        null
    }

    fun acknowledgeBlob(content: ByteArray, remoteSha: String, progress: Boolean) = synchronized(lock) {
        val expected = gitBlobSha(content)
        if (remoteSha != expected) throw IOException("GitHub returned a blob SHA that does not match the uploaded content")
        writeAtomically(blobFile(content), "blob-v1\n$expected\n")
        if (progress) resetAttempts()
    }

    fun invalidateBlobKeys(keys: List<String>) = synchronized(lock) {
        for (key in keys) File(directory, "$key.blob").delete()
    }

    fun published() = synchronized(lock) {
        resetAttempts()
        val records = directory.listFiles { file -> file.name.endsWith(".blob") }.orEmpty()
        for (file in records.sortedByDescending(File::lastModified).drop(MAX_RETAINED_BLOBS)) file.delete()
    }

    private fun blobFile(content: ByteArray): File = File(directory, "${contentKey(content)}.blob")

    private fun rateException(state: RateState): GitHubRateLimitException = GitHubRateLimitPolicy.exception(
        state.statusCode, state.retryAt, state.attempts <= GitHubRateLimitPolicy.MAX_AUTOMATIC_RETRIES, nowMillis()
    )

    private fun readRateState(): RateState? = readSmallFile(File(directory, RATE_FILE))?.let(RateState::decode)

    private fun resetAttempts() {
        val state = readRateState()
        if (state != null && state.retryAt > nowMillis()) {
            // 另一个并发请求已经遇到限流时，成功进展不能提前解除冷却
            writeAtomically(File(directory, RATE_FILE), state.copy(attempts = 0).encode())
        } else {
            File(directory, RATE_FILE).delete()
        }
    }

    private fun readSmallFile(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(MAX_RECORD_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > MAX_RECORD_BYTES) null else String(bytes, 0, count, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun writeAtomically(file: File, text: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create private GitHub staging directory")
        val temporary = File.createTempFile("checkpoint-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Cannot publish GitHub staging checkpoint")
        } finally {
            temporary.delete()
        }
    }

    private data class RateState(val statusCode: Int, val retryAt: Long, val attempts: Int) {
        fun encode(): String {
            val record = "rate-v1\n$statusCode\n$retryAt\n$attempts\n"
            return record + contentKey(record.toByteArray(Charsets.UTF_8))
        }

        companion object {
            fun decode(record: String): RateState? {
                val fields = record.split('\n')
                if (!validChecksum(fields)) return null
                return parseFields(fields)
            }

            private fun validChecksum(fields: List<String>): Boolean {
                if (fields.size != 5 || fields[0] != "rate-v1") return false
                val prefix = fields.take(4).joinToString("\n", postfix = "\n")
                return fields[4] == contentKey(prefix.toByteArray(Charsets.UTF_8))
            }

            private fun parseFields(fields: List<String>): RateState? {
                val status = fields[1].toIntOrNull() ?: return null
                val retryAt = fields[2].toLongOrNull() ?: return null
                val attempts = fields[3].toIntOrNull() ?: return null
                if (!validFields(status, retryAt, attempts)) return null
                return RateState(status, retryAt, attempts)
            }

            private fun validFields(status: Int, retryAt: Long, attempts: Int): Boolean =
                (status == 403 || status == 429) && retryAt >= 0L && attempts in 0..4
        }
    }

    companion object {
        private const val MAX_RECORD_BYTES = 256
        private const val MAX_RETAINED_BLOBS = 8_192
        private const val RATE_FILE = "rate-limit"
        private val locks = Array(32) { Any() }

        fun namespace(apiBase: String, owner: String, repo: String): String = contentKey(
            "$apiBase\u0000${owner.lowercase()}/${repo.lowercase()}".toByteArray(Charsets.UTF_8)
        )

        fun contentKey(content: ByteArray): String = digestHex("SHA-256", content)

        fun gitBlobSha(content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${content.size}\u0000".toByteArray(Charsets.UTF_8))
            return digest.digest(content).joinToString("") { "%02x".format(it) }
        }

        private fun digestHex(algorithm: String, content: ByteArray): String = MessageDigest.getInstance(algorithm)
            .digest(content).joinToString("") { "%02x".format(it) }
    }
}
