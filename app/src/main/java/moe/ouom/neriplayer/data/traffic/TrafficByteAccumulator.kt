package moe.ouom.neriplayer.data.traffic

internal class TrafficByteAccumulator(
    private val thresholdBytes: Long = DEFAULT_FLUSH_THRESHOLD_BYTES,
    private val onFlush: (Long) -> Unit
) {
    private var pendingBytes = 0L

    fun add(bytes: Long) {
        if (bytes <= 0L) return
        pendingBytes += bytes
        if (pendingBytes >= thresholdBytes) {
            flush()
        }
    }

    fun flush() {
        val bytes = pendingBytes
        if (bytes <= 0L) return
        pendingBytes = 0L
        onFlush(bytes)
    }

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD_BYTES: Long = 256L * 1024L
    }
}
