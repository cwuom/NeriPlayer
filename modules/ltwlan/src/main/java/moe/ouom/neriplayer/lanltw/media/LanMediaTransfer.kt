package moe.ouom.neriplayer.lanltw.media

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal class LanMediaTransfer(
    private val openStream: (Long, Long?) -> LanMediaStreamInput,
    private val onClosed: (LanMediaTransfer) -> Unit
) {
    @Volatile var cancelled = false
        private set
    private var closing = false
    private var current: InputStream? = null

    fun open(offset: Long, length: Long?): LanMediaStreamInput {
        checkActive()
        val input = openStream(offset, length)
        val attached = synchronized(this) {
            if (cancelled) false else {
                current = input.stream
                true
            }
        }
        if (!attached) {
            input.stream.close()
            throw IOException("Stream closed")
        }
        return input
    }

    fun discardInput() {
        val input = synchronized(this) { current.also { current = null } }
        input?.close()
    }

    fun responseBody(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = readBody { super.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            readBody { super.read(bytes, offset, length) }

        override fun close() = this@LanMediaTransfer.close()
    }

    private inline fun readBody(read: () -> Int): Int {
        checkActive()
        val count = try {
            read()
        } catch (_: Exception) {
            // data source exceptions can contain authenticated origin URLs
            throw IOException("Media source read failed")
        }
        checkActive()
        return count
    }

    fun close() = finish(openingMayContinue = false)
    fun cancel() = finish(openingMayContinue = true)

    private fun finish(openingMayContinue: Boolean) {
        val input = synchronized(this) {
            cancelled = true
            if (closing || (current == null && openingMayContinue)) return
            closing = true
            current.also { current = null }
        }
        try {
            input?.close()
        } catch (_: Exception) {
            throw IOException("Media source close failed")
        } finally {
            onClosed(this)
        }
    }

    private fun checkActive() {
        if (cancelled) throw IOException("Stream closed")
    }
}
