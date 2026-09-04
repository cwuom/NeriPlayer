package moe.ouom.neriplayer.core.download.resource

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException

/**
 * 在写入前为每个文件系统维护可回收的空间预留
 *
 * 预留只存在于当前进程，进程退出后不会留下锁文件。未知长度的传输按小块增长，
 * 因此不会把整个磁盘一次性占满，也能在真正写入前报告可恢复的空间不足
 */
internal class DownloadStorageSpaceGuard(
    private val minimumFreeBytes: Long = DEFAULT_MINIMUM_FREE_BYTES,
    private val unknownReservationBytes: Long = DEFAULT_UNKNOWN_RESERVATION_BYTES,
    private val usableSpaceOf: (File) -> Long = { root -> root.usableSpace }
) {
    init {
        require(minimumFreeBytes >= 0L) {
            "minimumFreeBytes must not be negative"
        }
        require(unknownReservationBytes > 0L) {
            "unknownReservationBytes must be positive"
        }
    }

    data class Snapshot(
        val rootPath: String,
        val usableBytes: Long,
        val reservedBytes: Long,
        val ownerCount: Int
    ) {
        val freeAfterReservations: Long
            get() = usableBytes - reservedBytes
    }

    class Lease internal constructor(
        private val guard: DownloadStorageSpaceGuard,
        internal val rootPath: String,
        internal val ownerKey: String,
        initialReservedBytes: Long
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private var reservedBytes = initialReservedBytes

        /** 确保从当前文件开始累计写入的字节数已经被预留 */
        fun ensureAdditionalBytes(totalAdditionalBytes: Long) {
            if (totalAdditionalBytes < 0L) return
            guard.ensureCapacity(this, totalAdditionalBytes)
        }

        internal fun reservedBytes(): Long = synchronized(this) { reservedBytes }

        internal fun setReservedBytes(value: Long) {
            synchronized(this) {
                reservedBytes = value
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                guard.release(this)
            }
        }
    }

    private data class RootState(
        val root: File,
        var reservedBytes: Long = 0L,
        val owners: MutableMap<String, Lease> = linkedMapOf()
    )

    private val stateLock = Any()
    private val statesByRoot = linkedMapOf<String, RootState>()

    /**
     * 用空间租约包装输出流。关闭输出流时会自动释放租约，调用方无需额外记账
     */
    fun guardOutput(
        output: OutputStream,
        root: File,
        ownerKey: String,
        expectedAdditionalBytes: Long? = null
    ): DownloadSpaceGuardedOutputStream {
        val lease = try {
            reserve(
                root = root,
                ownerKey = ownerKey,
                expectedAdditionalBytes = expectedAdditionalBytes
            )
        } catch (error: Throwable) {
            // 预留失败时输出流已经由调用方创建，必须在这里关闭，避免 FD 泄漏
            runCatching { output.close() }.onFailure(error::addSuppressed)
            throw error
        }
        return DownloadSpaceGuardedOutputStream(output, lease)
    }

    fun reserve(
        root: File,
        ownerKey: String,
        expectedAdditionalBytes: Long? = null
    ): Lease {
        val normalizedOwner = ownerKey.trim()
        require(normalizedOwner.isNotEmpty()) { "ownerKey must not be blank" }
        val existingRoot = resolveExistingRoot(root)
        val rootPath = existingRoot.path
        val initialBytes = expectedAdditionalBytes
            ?.coerceAtLeast(0L)
            ?: unknownReservationBytes
        synchronized(stateLock) {
            val existingState = statesByRoot[rootPath]
            val state = existingState ?: RootState(root = existingRoot)
            check(normalizedOwner !in state.owners) {
                "space reservation owner already exists: $normalizedOwner"
            }
            ensureAvailableLocked(
                state = state,
                additionalBytes = initialBytes
            )
            val lease = Lease(
                guard = this,
                rootPath = rootPath,
                ownerKey = normalizedOwner,
                initialReservedBytes = initialBytes
            )
            if (existingState == null) {
                statesByRoot[rootPath] = state
            }
            state.owners[normalizedOwner] = lease
            state.reservedBytes = safeAdd(state.reservedBytes, initialBytes)
            return lease
        }
    }

    fun snapshot(root: File): Snapshot {
        val existingRoot = resolveExistingRoot(root)
        val rootPath = existingRoot.path
        synchronized(stateLock) {
            val state = statesByRoot[rootPath]
            val reservedBytes = state?.reservedBytes ?: 0L
            return Snapshot(
                rootPath = rootPath,
                usableBytes = readUsableSpace(existingRoot),
                reservedBytes = reservedBytes,
                ownerCount = state?.owners?.size ?: 0
            )
        }
    }

    private fun ensureCapacity(
        lease: Lease,
        totalAdditionalBytes: Long
    ) {
        synchronized(stateLock) {
            val state = statesByRoot[lease.rootPath]
                ?: throw DownloadStorageSpaceException(
                    rootPath = lease.rootPath,
                    usableBytes = 0L,
                    reservedBytes = 0L,
                    requestedBytes = totalAdditionalBytes,
                    minimumFreeBytes = minimumFreeBytes
                )
            val currentReservedBytes = lease.reservedBytes()
            val additionalBytes = (totalAdditionalBytes - currentReservedBytes)
                .coerceAtLeast(0L)
            ensureAvailableLocked(
                state = state,
                additionalBytes = additionalBytes
            )
            if (additionalBytes > 0L) {
                state.reservedBytes = safeAdd(state.reservedBytes, additionalBytes)
                lease.setReservedBytes(safeAdd(currentReservedBytes, additionalBytes))
            }
        }
    }

    private fun release(lease: Lease) {
        synchronized(stateLock) {
            val state = statesByRoot[lease.rootPath] ?: return
            val removed = state.owners.remove(lease.ownerKey) ?: return
            state.reservedBytes = (state.reservedBytes - removed.reservedBytes())
                .coerceAtLeast(0L)
            if (state.owners.isEmpty()) {
                statesByRoot.remove(lease.rootPath)
            }
        }
    }

    private fun ensureAvailableLocked(
        state: RootState,
        additionalBytes: Long
    ) {
        val usableBytes = readUsableSpace(state.root)
        val requiredReservedBytes = safeAdd(state.reservedBytes, additionalBytes)
        val requiredBytes = safeAdd(requiredReservedBytes, minimumFreeBytes)
        if (usableBytes < requiredBytes) {
            throw DownloadStorageSpaceException(
                rootPath = state.root.path,
                usableBytes = usableBytes,
                reservedBytes = state.reservedBytes,
                requestedBytes = additionalBytes,
                minimumFreeBytes = minimumFreeBytes
            )
        }
    }

    private fun readUsableSpace(root: File): Long {
        return runCatching { usableSpaceOf(root).coerceAtLeast(0L) }
            .getOrDefault(0L)
    }

    private fun resolveExistingRoot(root: File): File {
        var candidate = if (root.isDirectory) root else root.parentFile ?: root
        while (!candidate.exists() && candidate.parentFile != null) {
            candidate = requireNotNull(candidate.parentFile)
        }
        return runCatching { candidate.canonicalFile }
            .getOrElse { candidate.absoluteFile }
    }

    private fun safeAdd(first: Long, second: Long): Long {
        if (second <= 0L) return first.coerceAtLeast(0L)
        return if (first > Long.MAX_VALUE - second) {
            Long.MAX_VALUE
        } else {
            first + second
        }
    }

    companion object {
        const val DEFAULT_MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_UNKNOWN_RESERVATION_BYTES = 8L * 1024L * 1024L
        val global: DownloadStorageSpaceGuard by lazy { DownloadStorageSpaceGuard() }
    }
}

internal class DownloadSpaceGuardedOutputStream(
    output: OutputStream,
    private val lease: DownloadStorageSpaceGuard.Lease
) : OutputStream() {
    private val delegate = output
    private var writtenBytes = 0L

    override fun write(oneByte: Int) {
        lease.ensureAdditionalBytes(safeAdd(writtenBytes, 1L))
        delegate.write(oneByte)
        writtenBytes = safeAdd(writtenBytes, 1L)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "invalid byte range: offset=$offset, length=$length, size=${bytes.size}"
        }
        if (length == 0) return
        lease.ensureAdditionalBytes(safeAdd(writtenBytes, length.toLong()))
        delegate.write(bytes, offset, length)
        writtenBytes = safeAdd(writtenBytes, length.toLong())
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        var closeError: Throwable? = null
        try {
            delegate.close()
        } catch (error: Throwable) {
            closeError = error
        } finally {
            lease.close()
        }
        closeError?.let { error -> throw error }
    }

    private fun safeAdd(first: Long, second: Long): Long {
        return if (first > Long.MAX_VALUE - second) {
            Long.MAX_VALUE
        } else {
            first + second
        }
    }
}

internal class DownloadStorageSpaceException(
    val rootPath: String,
    val usableBytes: Long,
    val reservedBytes: Long,
    val requestedBytes: Long,
    val minimumFreeBytes: Long
) : IOException(
    "download storage space is insufficient: root=$rootPath, " +
        "usable=$usableBytes, reserved=$reservedBytes, " +
        "requested=$requestedBytes, minimumFree=$minimumFreeBytes"
)

internal const val DOWNLOAD_STORAGE_SPACE_ERROR_CODE = "INSUFFICIENT_STORAGE"

internal class DownloadStorageSpaceDeferredException(
    val operationId: String
) : CancellationException(
    "download operation waits for storage space: $operationId"
)

internal fun containsDownloadStorageSpaceFailure(error: Throwable): Boolean {
    return generateSequence(error) { it.cause }
        .any { cause ->
            cause is DownloadStorageSpaceException ||
                cause.message.orEmpty().let(::looksLikeNoSpaceFailure)
        }
}

/** Provider 或文件系统可能在预留之后才报告 ENOSPC，也必须进入同一恢复状态 */
private fun looksLikeNoSpaceFailure(message: String): Boolean {
    val normalized = message.trim().uppercase(Locale.ROOT)
    if (normalized.isEmpty()) return false
    return normalized.contains("ENOSPC") ||
        normalized.contains("NO SPACE LEFT") ||
        normalized.contains("DISK FULL") ||
        normalized.contains("INSUFFICIENT STORAGE") ||
        normalized.contains("STORAGE FULL")
}
