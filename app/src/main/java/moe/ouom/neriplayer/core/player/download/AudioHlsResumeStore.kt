package moe.ouom.neriplayer.core.player.download

import java.io.EOFException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject

/**
 * HLS 分段恢复凭据和 durable 前缀的存储边界
 *
 * playlist 解析、文件摘要和 checkpoint 持久化都留在这里，下载管理器只负责调度调用。
 * 只有 checkpoint 原子落盘后才发布内存状态，进程重启不会误用未落盘的尾部
 */
internal class AudioHlsResumeStore(
    private val checkpointFileFor: (File) -> File = {
        ManagedDownloadStorage.buildWorkingHlsCheckpointFile(it)
    },
    private val readBufferBytes: Int = DEFAULT_READ_BUFFER_BYTES
) {
    private val statesByWorkingPath = ConcurrentHashMap<String, AudioDownloadManager.HlsResumeState>()

    fun buildPlaylistFingerprint(
        segmentUrls: List<String>,
        playlistText: String? = null
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("neriplayer-hls-playlist-v2".toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        updateFingerprintField(digest, segmentUrls.size.toString())
        segmentUrls.forEachIndexed { index, segmentUrl ->
            updateFingerprintField(digest, index.toString())
            updateFingerprintField(digest, canonicalSegmentUri(segmentUrl))
        }
        playlistText
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter { line ->
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) ||
                    line.startsWith("#EXTINF:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true)
            }
            ?.forEachIndexed { index, line ->
                updateFingerprintField(digest, "metadata:$index")
                updateFingerprintField(digest, line)
            }
        return digestHex(digest)
    }

    fun serialize(state: AudioDownloadManager.HlsResumeState): String {
        return JSONObject().apply {
            put("format", "hls-resume-v2")
            put("playlistDigestSha256", state.playlistFingerprint)
            put("nextSegmentIndex", state.nextSegmentIndex)
            put("durableBytes", state.downloadedBytes.coerceAtLeast(0L))
            put("durablePrefixSha256", state.durablePrefixSha256)
            state.operationId.takeIf(String::isNotBlank)?.let { put("operationId", it) }
            state.mediaSequence?.let { put("mediaSequence", it) }
        }.toString()
    }

    fun deserialize(raw: String?): AudioDownloadManager.HlsResumeState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val playlistFingerprint = json.optString("playlistDigestSha256")
                .takeIf(String::isNotBlank)
                ?: json.optString("playlistFingerprint")
                    .takeIf(String::isNotBlank)
                ?: return@runCatching null
            if (!SHA256_HEX_REGEX.matches(playlistFingerprint)) return@runCatching null
            val nextSegmentIndex = json.getInt("nextSegmentIndex")
            val durableBytes = json.optLong(
                "durableBytes",
                json.optLong("downloadedBytes", -1L)
            )
            val durablePrefixSha256 = json.optString("durablePrefixSha256")
                .takeIf(String::isNotBlank)
            val operationId = json.optString("operationId")
                .takeIf(String::isNotBlank)
                .orEmpty()
            val mediaSequence = if (json.has("mediaSequence")) {
                json.optLong("mediaSequence").takeIf { it >= 0L }
            } else {
                null
            }
            if (
                nextSegmentIndex < 0 ||
                durableBytes < 0L ||
                durablePrefixSha256 == null ||
                !SHA256_HEX_REGEX.matches(durablePrefixSha256)
            ) {
                return@runCatching null
            }
            AudioDownloadManager.HlsResumeState(
                playlistFingerprint = playlistFingerprint,
                nextSegmentIndex = nextSegmentIndex,
                downloadedBytes = durableBytes,
                durablePrefixSha256 = durablePrefixSha256,
                operationId = operationId,
                mediaSequence = mediaSequence
            )
        }.getOrNull()
    }

    fun isCompatible(
        state: AudioDownloadManager.HlsResumeState,
        actualFileLength: Long,
        actualPrefixSha256: String,
        segmentCount: Int
    ): Boolean {
        return SHA256_HEX_REGEX.matches(state.playlistFingerprint) &&
            state.nextSegmentIndex in 0..segmentCount &&
            (state.nextSegmentIndex > 0 || state.downloadedBytes == 0L) &&
            actualFileLength >= state.downloadedBytes &&
            state.downloadedBytes >= 0L &&
            SHA256_HEX_REGEX.matches(state.durablePrefixSha256) &&
            state.durablePrefixSha256.equals(actualPrefixSha256, ignoreCase = true)
    }

    fun isOwnedByOperation(
        state: AudioDownloadManager.HlsResumeState,
        operationId: String
    ): Boolean {
        val normalizedOperationId = operationId.trim()
        return normalizedOperationId.isNotBlank() &&
            state.operationId.trim() == normalizedOperationId
    }

    fun sha256FilePrefix(file: File, byteCount: Long): String {
        return digestHex(sha256FilePrefixDigest(file, byteCount))
    }

    fun sha256FilePrefixDigest(file: File, byteCount: Long): MessageDigest {
        require(byteCount >= 0L) { "HLS durable byte count must not be negative" }
        val digest = MessageDigest.getInstance("SHA-256")
        if (byteCount == 0L) return digest
        var remaining = byteCount
        file.inputStream().use { input ->
            val buffer = ByteArray(readBufferBytes)
            while (remaining > 0L) {
                val read = input.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), remaining).toInt()
                )
                if (read <= 0) {
                    throw EOFException(
                        "HLS durable prefix is shorter than checkpoint: expected=$byteCount"
                    )
                }
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest
    }

    fun digestHexSnapshot(
        digest: MessageDigest,
        file: File,
        byteCount: Long
    ): String {
        val snapshot = runCatching { digest.clone() as? MessageDigest }.getOrNull()
        return snapshot?.let(::digestHex) ?: sha256FilePrefix(file, byteCount)
    }

    fun truncate(file: File, byteCount: Long) {
        java.io.RandomAccessFile(file, "rw").use { randomAccessFile ->
            randomAccessFile.setLength(byteCount)
            randomAccessFile.fd.sync()
        }
    }

    fun remember(
        destFile: File,
        playlistFingerprint: String,
        nextSegmentIndex: Int,
        durableBytes: Long,
        durablePrefixSha256: String,
        operationId: String,
        mediaSequence: Long?
    ) {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = playlistFingerprint,
            nextSegmentIndex = nextSegmentIndex,
            downloadedBytes = durableBytes.coerceAtLeast(0L),
            durablePrefixSha256 = durablePrefixSha256,
            operationId = operationId,
            mediaSequence = mediaSequence
        )
        val path = destFile.absolutePath
        val previousState = statesByWorkingPath[path]
        try {
            persist(destFile, state)
            statesByWorkingPath[path] = state
        } catch (error: Throwable) {
            if (previousState == null) {
                statesByWorkingPath.remove(path)
                deletePersisted(destFile)
            } else {
                statesByWorkingPath[path] = previousState
            }
            throw error
        }
    }

    fun resolve(
        destFile: File,
        playlistFingerprint: String,
        operationId: String = ""
    ): AudioDownloadManager.HlsResumeState? {
        val state = statesByWorkingPath[destFile.absolutePath]
            ?: readPersisted(destFile)?.also { persisted ->
                statesByWorkingPath[destFile.absolutePath] = persisted
            }
            ?: return null
        return state.takeIf {
            it.playlistFingerprint == playlistFingerprint &&
                isOwnedByOperation(it, operationId)
        }
    }

    fun has(destFile: File?): Boolean {
        return destFile != null && (
            statesByWorkingPath.containsKey(destFile.absolutePath) ||
                checkpointFileFor(destFile).exists()
            )
    }

    fun clear(destFile: File?) {
        destFile ?: return
        statesByWorkingPath.remove(destFile.absolutePath)
        deletePersisted(destFile)
    }

    private fun persist(destFile: File, state: AudioDownloadManager.HlsResumeState) {
        val checkpointFile = checkpointFileFor(destFile)
        runCatching {
            checkpointFile.parentFile?.mkdirs()
            ManagedDownloadAtomicFile.writeTextAtomically(
                target = checkpointFile,
                content = serialize(state)
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "写入 HLS 恢复点失败: ${checkpointFile.name}", error)
            throw error
        }
    }

    private fun readPersisted(destFile: File): AudioDownloadManager.HlsResumeState? {
        val checkpointFile = checkpointFileFor(destFile)
        if (!checkpointFile.exists() || !checkpointFile.isFile) return null
        return runCatching {
            deserialize(checkpointFile.readText(Charsets.UTF_8))
        }.onFailure { error ->
            NPLogger.w(TAG, "读取 HLS 恢复点失败: ${checkpointFile.name}, ${error.message}")
        }.getOrNull()
    }

    private fun deletePersisted(destFile: File) {
        val checkpointFile = checkpointFileFor(destFile)
        if (checkpointFile.exists()) {
            runCatching { checkpointFile.delete() }
        }
    }

    private fun canonicalSegmentUri(url: String): String {
        val volatileQueryKeys = setOf(
            "alr", "expire", "expires", "lsig", "n", "sig", "signature", "sp", "st", "token"
        )
        return runCatching {
            val uri = java.net.URI(url.trim())
            val query = uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val key = part.substringBefore('=').lowercase()
                    part.takeIf { key.isNotBlank() && key !in volatileQueryKeys }
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf(String::isNotBlank)
            buildString {
                if (!uri.scheme.isNullOrBlank()) {
                    append(uri.scheme.orEmpty().lowercase())
                    append("://")
                    append(uri.rawAuthority.orEmpty().lowercase())
                }
                append(uri.rawPath.orEmpty())
                if (query != null) {
                    append('?')
                    append(query)
                }
            }
        }.getOrElse { url.substringBefore('#').substringBefore('?') }
    }

    private fun updateFingerprintField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte()
            )
        )
        digest.update(bytes)
    }

    private fun digestHex(digest: MessageDigest): String {
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val TAG = "AudioHlsResumeStore"
        private const val DEFAULT_READ_BUFFER_BYTES = 64 * 1024
        private val SHA256_HEX_REGEX = Regex("[0-9a-fA-F]{64}")
    }
}
