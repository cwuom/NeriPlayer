package moe.ouom.neriplayer.core.player.lan.relay

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.lanltw.media.LanMediaStreamInput

/** 同步播放的成员共用近期音频块，远距离跳转按字节位置重新打开同一音源 */
internal class LanRelayWindow(
    initialLength: Long?,
    initial: InputStream,
    private val stopUpstream: () -> Unit = {},
    private val reopen: (Long) -> InputStream
) : Closeable {
    // 成员的预读位置可能不同，各自顺序读取时可以接着使用原来的上游连接
    private val cursors = linkedMapOf(0L to initial)
    @Volatile private var length = initialLength
    private var minimumLength = 0L
    private val blocks = LinkedHashMap<Long, ByteArray>(64, .75f, true)
    private val pending = mutableMapOf<Long, CompletableFuture<ByteArray>>()
    private val workers = ThreadPoolExecutor(
        WORKERS, WORKERS, 20, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED_BLOCKS)
    ) { task -> Thread(task, "lan-relay").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }
    private val lock = Any()
    private var closed = false

    fun open(offset: Long, limit: Long?): LanMediaStreamInput {
        require(offset >= 0 && (limit == null || limit >= 0))
        val remaining = synchronized(lock) {
            if (closed) throw IOException("LOCAL_TRANSFER")
            length?.let { (it - offset).coerceAtLeast(0L) }
        }
        val size = if (limit == null) remaining else remaining?.coerceAtMost(limit)
        val stream = object : InputStream() {
            private var position = offset
            private val stopped = CompletableFuture<Unit>()

            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (stopped.isDone) throw IOException("LOCAL_TRANSFER")
                val left = (limit ?: Long.MAX_VALUE) - (position - offset)
                if (left <= 0 || position >= (length ?: Long.MAX_VALUE)) return -1
                val blockAt = position - position % BLOCK_BYTES
                val bytes = block(blockAt, stopped)
                val from = (position - blockAt).toInt()
                if (from >= bytes.size) return -1
                val count = minOf(len.toLong(), left, (bytes.size - from).toLong()).toInt()
                bytes.copyInto(buffer, off, from, from + count)
                position += count
                return count
            }
            override fun close() { stopped.complete(Unit) }
        }
        return LanMediaStreamInput(stream, size)
    }

    private fun block(at: Long, stopped: CompletableFuture<Unit>): ByteArray {
        val future = synchronized(lock) {
            if (closed || stopped.isDone) throw IOException("LOCAL_TRANSFER")
            blocks[at]?.let { return it }
            pending[at] ?: startBlock(at)
        }
        return try {
            val result = CompletableFuture.anyOf(future, stopped).get()
            if (stopped.isDone || result !is ByteArray) throw IOException("LOCAL_TRANSFER")
            result
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is IOException) throw cause
            throw IOException("LOCAL_TRANSFER", cause)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("LOCAL_TRANSFER", interrupted)
        }
    }

    private fun startBlock(at: Long): CompletableFuture<ByteArray> {
        val future = CompletableFuture<ByteArray>()
        pending[at] = future
        try {
            workers.execute { produceBlock(at, future) }
        } catch (rejected: RejectedExecutionException) {
            pending.remove(at)
            throw IOException("LOCAL_TRANSFER", rejected)
        }
        return future
    }

    private fun produceBlock(at: Long, future: CompletableFuture<ByteArray>) {
        try {
            val bytes = readBlock(at)
            synchronized(lock) {
                if (closed) throw IOException("LOCAL_TRANSFER")
                blocks[at] = bytes
                if (blocks.size > MAX_BLOCKS) blocks.remove(blocks.keys.first())
            }
            future.complete(bytes)
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
            if (failure is Error) throw failure
        } finally {
            synchronized(lock) { pending.remove(at) }
        }
    }

    private fun readBlock(at: Long): ByteArray {
        val expected = length
        val wanted = expected?.let { (it - at).coerceIn(0L, BLOCK_BYTES.toLong()).toInt() } ?: BLOCK_BYTES
        if (wanted == 0) return byteArrayOf()
        val input = synchronized(lock) {
            if (closed) throw IOException("LOCAL_TRANSFER")
            cursors.remove(at)
        } ?: reopen(at)
        var keepOpen = false
        try {
            synchronized(lock) {
                if (closed) throw IOException("LOCAL_TRANSFER")
            }
            val bytes = ByteArray(wanted)
            var count = 0
            while (count < wanted) {
                val n = input.read(bytes, count, wanted - count)
                if (n < 0) break
                if (n == 0) throw IOException("LOCAL_TRANSFER")
                count += n
            }
            if (expected != null && count < wanted) throw EOFException("LOCAL_TRANSFER")
            val displaced = synchronized(lock) {
                if (count > 0) minimumLength = maxOf(minimumLength, at + count)
                if (length == null && count < wanted && at + count == minimumLength) length = minimumLength
                if (!closed && count == wanted && at + count < (length ?: Long.MAX_VALUE)) {
                    keepOpen = true
                    val next = at + count
                    val previous = cursors.remove(next)
                    cursors[next] = input
                    if (cursors.size > WORKERS) cursors.remove(cursors.keys.first()) else previous
                } else null
            }
            runCatching { displaced?.close() }
            return if (count == wanted) bytes else bytes.copyOf(count)
        } finally {
            if (!keepOpen) runCatching { input.close() }
        }
    }

    override fun close() {
        val (idle, waiting) = synchronized(lock) {
            if (closed) return
            closed = true
            blocks.clear()
            (cursors.values.toList() to pending.values.toList()).also {
                cursors.clear()
                pending.clear()
            }
        }
        waiting.forEach { it.completeExceptionally(IOException("LOCAL_TRANSFER")) }
        try {
            stopUpstream()
        } finally {
            workers.queue.clear()
            if (idle.isNotEmpty()) workers.execute { idle.forEach { runCatching { it.close() } } }
            workers.shutdown()
        }
    }

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
        const val MAX_BLOCKS = 64
        const val WORKERS = 4
        const val MAX_QUEUED_BLOCKS = 16
    }
}
