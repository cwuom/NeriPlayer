package moe.ouom.neriplayer.core.download.resource

import android.os.StatFs
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
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
    private val usableSpaceOf: (File) -> Long = { root ->
        readDefaultUsableSpace(root)
    },
    private val storageVolumeKeyOf: (File) -> String = { root ->
        readDefaultStorageVolumeKey(root)
    }
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
        val ownerCount: Int,
        val usableSpaceKnown: Boolean = true
    ) {
        val freeAfterReservations: Long
            get() = usableBytes - reservedBytes
    }

    class Lease internal constructor(
        private val guard: DownloadStorageSpaceGuard,
        internal val storageVolumeKey: String,
        internal val rootPath: String,
        internal val ownerKey: String,
        initialReservedBytes: Long
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private var reservedBytes = initialReservedBytes

        /** 确保租约至少保留下一次写入所需的字节数 */
        fun ensureAdditionalBytes(requiredBytes: Long) {
            if (requiredBytes < 0L) return
            guard.ensureCapacity(this, requiredBytes)
        }

        internal fun consumeReservedBytes(writtenBytes: Long) {
            if (writtenBytes <= 0L) return
            guard.consume(this, writtenBytes)
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

    private data class VolumeState(
        var probeRoot: File,
        var reservedBytes: Long = 0L,
        val owners: MutableMap<String, Lease> = linkedMapOf()
    )

    private val stateLock = Any()
    private val statesByVolume = linkedMapOf<String, VolumeState>()

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
        val storageVolumeKey = resolveStorageVolumeKey(existingRoot)
        val initialBytes = expectedAdditionalBytes
            ?.coerceAtLeast(0L)
            ?: unknownReservationBytes
        synchronized(stateLock) {
            val existingState = statesByVolume[storageVolumeKey]
            val state = existingState ?: VolumeState(probeRoot = existingRoot)
            state.probeRoot = existingRoot
            check(normalizedOwner !in state.owners) {
                "space reservation owner already exists: $normalizedOwner"
            }
            ensureAvailableLocked(
                state = state,
                additionalBytes = initialBytes
            )
            val lease = Lease(
                guard = this,
                storageVolumeKey = storageVolumeKey,
                rootPath = rootPath,
                ownerKey = normalizedOwner,
                initialReservedBytes = initialBytes
            )
            if (existingState == null) {
                statesByVolume[storageVolumeKey] = state
            }
            state.owners[normalizedOwner] = lease
            state.reservedBytes = safeAdd(state.reservedBytes, initialBytes)
            return lease
        }
    }

    fun snapshot(root: File): Snapshot {
        val existingRoot = resolveExistingRoot(root)
        val rootPath = existingRoot.path
        val storageVolumeKey = resolveStorageVolumeKey(existingRoot)
        synchronized(stateLock) {
            val state = statesByVolume[storageVolumeKey]
            val reservedBytes = state?.reservedBytes ?: 0L
            val probe = readUsableSpace(existingRoot)
            return Snapshot(
                rootPath = rootPath,
                usableBytes = probe.bytes,
                reservedBytes = reservedBytes,
                ownerCount = state?.owners?.size ?: 0,
                usableSpaceKnown = probe.known
            )
        }
    }

    private fun ensureCapacity(
        lease: Lease,
        totalAdditionalBytes: Long
    ) {
        synchronized(stateLock) {
            val state = statesByVolume[lease.storageVolumeKey]
                ?: throw DownloadStorageSpaceException(
                    rootPath = lease.rootPath,
                    usableBytes = 0L,
                    reservedBytes = 0L,
                    requestedBytes = totalAdditionalBytes,
                    minimumFreeBytes = minimumFreeBytes,
                    ownerReservedBytes = 0L,
                    usableSpaceKnown = false
                )
            val currentReservedBytes = lease.reservedBytes()
            val additionalBytes = (totalAdditionalBytes - currentReservedBytes)
                .coerceAtLeast(0L)
            ensureAvailableLocked(
                state = state,
                additionalBytes = additionalBytes,
                ownerReservedBytes = currentReservedBytes
            )
            if (additionalBytes > 0L) {
                state.reservedBytes = safeAdd(state.reservedBytes, additionalBytes)
                lease.setReservedBytes(safeAdd(currentReservedBytes, additionalBytes))
            }
        }
    }

    private fun release(lease: Lease) {
        synchronized(stateLock) {
            val state = statesByVolume[lease.storageVolumeKey] ?: return
            val removed = state.owners.remove(lease.ownerKey) ?: return
            state.reservedBytes = (state.reservedBytes - removed.reservedBytes())
                .coerceAtLeast(0L)
            if (state.owners.isEmpty()) {
                statesByVolume.remove(lease.storageVolumeKey)
            }
        }
    }

    private fun consume(lease: Lease, writtenBytes: Long) {
        synchronized(stateLock) {
            val state = statesByVolume[lease.storageVolumeKey] ?: return
            if (state.owners[lease.ownerKey] !== lease) return
            val currentReservedBytes = lease.reservedBytes()
            val consumedBytes = writtenBytes.coerceAtMost(currentReservedBytes)
            if (consumedBytes <= 0L) return
            state.reservedBytes = (state.reservedBytes - consumedBytes).coerceAtLeast(0L)
            lease.setReservedBytes(currentReservedBytes - consumedBytes)
        }
    }

    private fun ensureAvailableLocked(
        state: VolumeState,
        additionalBytes: Long,
        ownerReservedBytes: Long = 0L
    ) {
        val probe = readUsableSpace(state.probeRoot)
        if (!probe.known) {
            throw DownloadStorageSpaceException(
                rootPath = state.probeRoot.path,
                usableBytes = 0L,
                reservedBytes = state.reservedBytes,
                requestedBytes = additionalBytes,
                minimumFreeBytes = minimumFreeBytes,
                ownerReservedBytes = ownerReservedBytes,
                usableSpaceKnown = false
            )
        }
        val usableBytes = probe.bytes
        val requiredReservedBytes = safeAdd(state.reservedBytes, additionalBytes)
        val requiredBytes = safeAdd(requiredReservedBytes, minimumFreeBytes)
        if (usableBytes < requiredBytes) {
            throw DownloadStorageSpaceException(
                rootPath = state.probeRoot.path,
                usableBytes = usableBytes,
                reservedBytes = state.reservedBytes,
                requestedBytes = additionalBytes,
                minimumFreeBytes = minimumFreeBytes,
                ownerReservedBytes = ownerReservedBytes,
                usableSpaceKnown = true
            )
        }
    }

    private data class UsableSpaceProbe(
        val bytes: Long,
        val known: Boolean
    )

    private fun readUsableSpace(root: File): UsableSpaceProbe {
        return runCatching { usableSpaceOf(root).coerceAtLeast(0L) }
            .fold(
                onSuccess = { bytes -> UsableSpaceProbe(bytes = bytes, known = true) },
                onFailure = { UsableSpaceProbe(bytes = 0L, known = false) }
            )
    }

    private fun resolveExistingRoot(root: File): File {
        var candidate = if (root.isDirectory) root else root.parentFile ?: root
        while (!candidate.exists() && candidate.parentFile != null) {
            candidate = requireNotNull(candidate.parentFile)
        }
        return runCatching { candidate.canonicalFile }
            .getOrElse { candidate.absoluteFile }
    }

    private fun resolveStorageVolumeKey(root: File): String {
        return runCatching { storageVolumeKeyOf(root) }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "path:${root.path}"
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

/** Android 的 StatFs 才能正确反映应用所在卷，JVM 测试环境再退回 NIO */
private fun readDefaultUsableSpace(root: File): Long {
    val statFsBytes = runCatching { StatFs(root.path).availableBytes }.getOrNull()
    // Android 单测里的 StatFs stub 可能返回 0；只有正值才覆盖 NIO，避免把
    // JVM 测试环境误判成磁盘已满。真实磁盘为 0 时 NIO 通常也会返回 0
    return statFsBytes
        ?.takeIf { bytes -> bytes > 0L }
        ?: Files.getFileStore(root.toPath()).usableSpace
}

private fun readDefaultStorageVolumeKey(root: File): String {
    val deviceId = runCatching { Os.stat(root.path).st_dev }.getOrNull()
    if (deviceId != null) return "device:$deviceId"
    val fileStore = Files.getFileStore(root.toPath())
    return "store:${fileStore.name()}:${fileStore.type()}:$fileStore"
}

internal class DownloadSpaceGuardedOutputStream(
    output: OutputStream,
    private val lease: DownloadStorageSpaceGuard.Lease
) : OutputStream() {
    private val delegate = output

    override fun write(oneByte: Int) {
        lease.ensureAdditionalBytes(1L)
        delegate.write(oneByte)
        lease.consumeReservedBytes(1L)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "invalid byte range: offset=$offset, length=$length, size=${bytes.size}"
        }
        if (length == 0) return
        lease.ensureAdditionalBytes(length.toLong())
        delegate.write(bytes, offset, length)
        lease.consumeReservedBytes(length.toLong())
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
}

internal class DownloadStorageSpaceException(
    val rootPath: String,
    val usableBytes: Long,
    val reservedBytes: Long,
    val requestedBytes: Long,
    val minimumFreeBytes: Long,
    val ownerReservedBytes: Long = 0L,
    val usableSpaceKnown: Boolean = true
) : IOException(
    "download storage space is insufficient: root=$rootPath, " +
        "usable=$usableBytes, reserved=$reservedBytes, " +
        "requested=$requestedBytes, minimumFree=$minimumFreeBytes"
)

internal const val DOWNLOAD_STORAGE_SPACE_ERROR_CODE = "INSUFFICIENT_STORAGE"

internal enum class DownloadStorageSpaceFailureKind {
    /** 进程内其它传输的预留暂时占用空间，不代表磁盘真的写不下 */
    RESERVATION_CONTENTION,
    /** 已知可用空间连当前写入和安全余量都无法容纳 */
    CAPACITY_EXHAUSTED,
    /** 无法可靠读取可用空间，不能据此误报磁盘已满 */
    PROBE_UNAVAILABLE,
    /** Provider/文件系统在实际写入时返回 ENOSPC 等确定错误 */
    PROVIDER_FAILURE
}

internal val DownloadStorageSpaceException.failureKind: DownloadStorageSpaceFailureKind
    get() {
        if (!usableSpaceKnown) return DownloadStorageSpaceFailureKind.PROBE_UNAVAILABLE
        val ownerRequired = if (ownerReservedBytes > Long.MAX_VALUE - requestedBytes) {
            Long.MAX_VALUE
        } else {
            ownerReservedBytes + requestedBytes
        }
        val minimumRequired = if (ownerRequired > Long.MAX_VALUE - minimumFreeBytes) {
            Long.MAX_VALUE
        } else {
            ownerRequired + minimumFreeBytes
        }
        return if (usableBytes < minimumRequired) {
            DownloadStorageSpaceFailureKind.CAPACITY_EXHAUSTED
        } else {
            DownloadStorageSpaceFailureKind.RESERVATION_CONTENTION
        }
    }

internal class DownloadStorageSpaceDeferredException(
    val operationId: String,
    val failureKind: DownloadStorageSpaceFailureKind =
        DownloadStorageSpaceFailureKind.CAPACITY_EXHAUSTED,
    val cancelAllDownloads: Boolean = failureKind.isDefinitive
) : CancellationException(
    "download operation waits for storage space: $operationId"
)

internal fun classifyDownloadStorageSpaceFailure(
    error: Throwable
): DownloadStorageSpaceFailureKind? {
    return generateSequence(error) { it.cause }
        .mapNotNull { cause ->
            when (cause) {
                is DownloadStorageSpaceException -> cause.failureKind
                else -> cause.message
                    ?.takeIf(::looksLikeNoSpaceFailure)
                    ?.let { DownloadStorageSpaceFailureKind.PROVIDER_FAILURE }
            }
        }
        .firstOrNull()
}

internal fun containsDownloadStorageSpaceFailure(error: Throwable): Boolean {
    return classifyDownloadStorageSpaceFailure(error) != null
}

internal val DownloadStorageSpaceFailureKind.isDefinitive: Boolean
    get() = this == DownloadStorageSpaceFailureKind.CAPACITY_EXHAUSTED ||
        this == DownloadStorageSpaceFailureKind.PROVIDER_FAILURE

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
