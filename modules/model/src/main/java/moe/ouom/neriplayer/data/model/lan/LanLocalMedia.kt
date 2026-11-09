package moe.ouom.neriplayer.data.model.lan

import kotlinx.serialization.Serializable

/** Public file identity; device paths and download credentials must never appear here. */
@Serializable
data class LanLocalMedia(
    val mediaId: String,
    val sha256: String,
    val byteLength: Long,
    val durationMs: Long,
    val mimeType: String,
    val presentation: LanFileResource? = null
) {
    val stableKey: String get() = "local:$sha256"
    fun valid(): Boolean = mediaId.matches(Regex("[a-f0-9-]{36}")) &&
        sha256.matches(Regex("[a-f0-9]{64}")) && byteLength in 1..MAX_BYTES &&
        durationMs > 0 && mimeType.startsWith("audio/") && mimeType.length <= 128 && (presentation == null || presentation.valid(8L * 1024 * 1024))
    companion object {
        const val MAX_BYTES: Long = 200L * 1024 * 1024
        const val MAX_PCM_BYTES: Long = 512L * 1024 * 1024
    }
}

@Serializable
data class LanFileResource(val mediaId: String, val sha256: String, val byteLength: Long, val mimeType: String) {
    fun valid(maxBytes: Long): Boolean = mediaId.matches(Regex("[a-f0-9-]{36}")) &&
        sha256.matches(Regex("[a-f0-9]{64}")) && byteLength in 1..maxBytes && mimeType.length in 1..128
}

/** Float32 PCM at the actual AudioContext rate, checked before decode allocation. */
fun lanPcmFits(durationUs: Long, sampleRate: Int, channels: Int): Boolean {
    if (durationUs <= 0 || sampleRate !in 8000..192000 || channels !in 1..2) return false
    return durationUs / 1_000_000.0 * sampleRate * channels * 4 <= LanLocalMedia.MAX_PCM_BYTES
}
