package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.logging.NPLogger

internal const val DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR =
    "DIRECTORY_CHANGE_IN_PROGRESS"

internal class DownloadStorageMutationDeferredException(
    operationId: String
) : CancellationException(
    "download operation must wait for directory change: $operationId"
)

internal fun shouldFenceDownloadForDirectoryMutation(
    state: ManagedLibraryProcessingState,
    inMemoryGateClosed: Boolean
): Boolean {
    return inMemoryGateClosed ||
        (
            state.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE &&
                state != ManagedLibraryProcessingState.Idle
            )
}

/**
 * 让下载根目录提交和迁移源收集按顺序执行
 */
internal class ManagedDownloadDirectoryMutationGate {
    internal inner class DownloadLease internal constructor() : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseDownloadLease()
            }
        }
    }

    internal inner class MutationLease internal constructor() : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseMutationLease()
            }
        }
    }

    private val mutationMutex = Mutex()
    private val stateLock = Any()
    private var mutationClosed = false
    private var activeDownloadLeases = 0
    private var drainWaiter: CompletableDeferred<Unit>? = null
    private var openWaiter: CompletableDeferred<Unit>? = null

    fun isClosed(): Boolean = synchronized(stateLock) { mutationClosed }

    fun tryAcquireDownloadLease(): DownloadLease? = synchronized(stateLock) {
        if (mutationClosed) {
            return@synchronized null
        }
        activeDownloadLeases += 1
        DownloadLease()
    }

    suspend fun closeAndDrain(): MutationLease {
        mutationMutex.lock()
        try {
            val waiter = synchronized(stateLock) {
                check(!mutationClosed) { "directory mutation gate already closed" }
                mutationClosed = true
                openWaiter = CompletableDeferred()
                if (activeDownloadLeases == 0) {
                    null
                } else {
                    CompletableDeferred<Unit>().also { drainWaiter = it }
                }
            }
            waiter?.await()
            return MutationLease()
        } catch (error: Throwable) {
            reopenAfterFailedClose()
            mutationMutex.unlock()
            throw error
        }
    }

    suspend fun awaitOpen() {
        while (true) {
            val waiter = synchronized(stateLock) {
                if (!mutationClosed) {
                    return
                }
                openWaiter
            }
            waiter?.await()
        }
    }

    private fun releaseDownloadLease() {
        val waiter = synchronized(stateLock) {
            check(activeDownloadLeases > 0) { "download lease count underflow" }
            activeDownloadLeases -= 1
            if (activeDownloadLeases == 0) {
                drainWaiter.also { drainWaiter = null }
            } else {
                null
            }
        }
        waiter?.complete(Unit)
    }

    private fun releaseMutationLease() {
        val waiter = synchronized(stateLock) {
            check(mutationClosed) { "directory mutation gate is not closed" }
            mutationClosed = false
            drainWaiter = null
            openWaiter.also { openWaiter = null }
        }
        waiter?.complete(Unit)
        mutationMutex.unlock()
    }

    private fun reopenAfterFailedClose() {
        val waiter = synchronized(stateLock) {
            if (!mutationClosed) {
                return@synchronized null
            }
            mutationClosed = false
            drainWaiter = null
            openWaiter.also { openWaiter = null }
        }
        waiter?.complete(Unit)
    }
}

internal object ManagedDownloadDirectoryMutationFence {
    private const val TAG = "DirectoryMutationFence"
    private val gate = ManagedDownloadDirectoryMutationGate()

    /** 同步解析播放 URI 时快速检查内存闸门和已恢复的持久闸门 */
    fun isActiveFast(context: Context): Boolean {
        if (gate.isClosed()) {
            return true
        }
        return runCatching {
            shouldFenceDownloadForDirectoryMutation(
                state = ManagedLibraryProcessingCoordinator.restoreImmediately(
                    context.applicationContext
                ),
                inMemoryGateClosed = false
            )
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "同步读取目录迁移状态失败，保守阻止旧播放引用: ${error.message}"
            )
            true
        }
    }

    suspend fun isActive(context: Context): Boolean {
        if (gate.isClosed()) {
            return true
        }
        return try {
            val state = ManagedLibraryProcessingCoordinator.restore(context.applicationContext)
            shouldFenceDownloadForDirectoryMutation(
                state = state,
                inMemoryGateClosed = false
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "读取目录迁移状态失败，保守延后下载提交: ${error.message}"
            )
            true
        }
    }

    suspend fun deferOperationIfActive(
        context: Context,
        operationId: String
    ): Boolean {
        if (!isActive(context)) {
            return false
        }
        try {
            DownloadExecutionRoomStore.markWaitingForStorageMutation(
                context = context.applicationContext,
                operationId = operationId,
                errorCode = DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "持久化目录迁移等待状态失败，保留现有 operation 供恢复: " +
                    "operationId=$operationId, error=${error.message}"
            )
        }
        return true
    }

    suspend fun acquireCommitLeaseOrNull(
        context: Context,
        operationId: String
    ): AutoCloseable? {
        if (deferOperationIfActive(context, operationId)) {
            return null
        }
        val lease = gate.tryAcquireDownloadLease() ?: run {
            deferOperationIfActive(context, operationId)
            return null
        }
        return try {
            if (deferOperationIfActive(context, operationId)) {
                lease.close()
                null
            } else {
                lease
            }
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    suspend fun closeAndDrain(): AutoCloseable = gate.closeAndDrain()

    /**
     * 让破坏性库删除和目录迁移互斥执行
     */
    suspend fun acquireDeleteLeaseOrNull(context: Context): AutoCloseable? {
        if (isActive(context)) {
            return null
        }
        val lease = gate.tryAcquireDownloadLease() ?: return null
        return try {
            if (isActive(context)) {
                lease.close()
                null
            } else {
                lease
            }
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    suspend fun awaitOpen() = gate.awaitOpen()
}
