package moe.ouom.neriplayer.data.traffic

internal class TrafficByteAccumulator(
    private val thresholdBytes: Long = DEFAULT_FLUSH_THRESHOLD_BYTES,
    private val onFlush: (Long) -> Unit
) {
    private val lock = Any()
    private var pendingBytes = 0L

    fun add(bytes: Long) {
        if (bytes <= 0L) return
        val bytesToFlush = synchronized(lock) {
            pendingBytes = if (Long.MAX_VALUE - pendingBytes < bytes) {
                Long.MAX_VALUE
            } else {
                pendingBytes + bytes
            }
            if (pendingBytes >= thresholdBytes) {
                pendingBytes.also { pendingBytes = 0L }
            } else {
                0L
            }
        }
        if (bytesToFlush > 0L) {
            onFlush(bytesToFlush)
        }
    }

    fun flush() {
        val bytesToFlush = synchronized(lock) {
            pendingBytes.also { pendingBytes = 0L }
        }
        if (bytesToFlush > 0L) {
            onFlush(bytesToFlush)
        }
    }

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD_BYTES: Long = 256L * 1024L
    }
}
