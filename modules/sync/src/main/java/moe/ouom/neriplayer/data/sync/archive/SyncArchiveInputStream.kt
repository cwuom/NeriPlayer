package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayInputStream
import java.io.InputStream

internal class SyncArchiveInputStream(
    private val refs: List<SyncArchiveRef>,
    private val cache: SyncArchiveCache
) : InputStream() {
    private var next = 0
    private var current = ByteArrayInputStream(ByteArray(0))

    private fun advance(): Boolean {
        while (current.available() == 0) {
            if (next == refs.size) return false
            current = ByteArrayInputStream(cache.readRaw(refs[next++]))
        }
        return true
    }

    override fun read(): Int = if (advance()) current.read() else -1

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        validateRange(bytes, offset, length)
        if (length == 0) return 0
        return if (advance()) current.read(bytes, offset, length) else -1
    }

    private fun validateRange(bytes: ByteArray, offset: Int, length: Int) {
        require(offset in 0..bytes.size)
        require(length in 0..bytes.size - offset)
    }
}
