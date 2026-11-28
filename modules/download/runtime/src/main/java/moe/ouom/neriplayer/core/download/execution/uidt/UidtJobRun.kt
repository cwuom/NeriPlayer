package moe.ouom.neriplayer.core.download.execution.uidt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger

/**
 * 一次 UIDT 执行的各个步骤
 * onStartJob 在主线程且系统只等几秒，调度锁、通知 Binder 和下载宿主初始化都可能阻塞，
 * 所以这些步骤全部放到后台协程，只有 reportCompletion 自己决定是否回主线程
 */
internal class UidtJobRunSteps(
    /** 先登记通知持有者，避免共享通知在 setNotification 之后被刷新取消 */
    val acquireNotification: () -> Unit,
    /** 系统要求 onStartJob 后 10 秒内提供通知，排在调度锁之前 */
    val postNotification: () -> Unit,
    val markStarted: () -> Unit,
    /** 返回是否需要让系统重新调度 */
    val execute: suspend () -> Boolean,
    val onNotificationFailure: suspend () -> Unit,
    val cleanup: () -> Unit,
    val reportCompletion: suspend (wantsReschedule: Boolean) -> Unit
)

internal suspend fun runUidtJob(
    operationId: String,
    steps: UidtJobRunSteps
) {
    var wantsReschedule = false
    try {
        steps.acquireNotification()
        val notificationPosted = try {
            steps.postNotification()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.e(
                UIDT_RUN_TAG,
                "UIDT 前台通知初始化失败: operationId=$operationId, error=${error.message}",
                error
            )
            false
        }
        if (!notificationPosted) {
            wantsReschedule = true
            steps.onNotificationFailure()
            return
        }
        steps.markStarted()
        wantsReschedule = steps.execute()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        wantsReschedule = true
        NPLogger.e(
            UIDT_RUN_TAG,
            "UIDT 下载任务异常: operationId=$operationId, error=${error.message}",
            error
        )
    } finally {
        withContext(NonCancellable) {
            steps.cleanup()
            steps.reportCompletion(wantsReschedule)
        }
    }
}

/**
 * 本地登记和控制器登记必须同锁串行
 * 后台收尾若插在两者之间，会先释放一个控制器尚未登记的持有者，之后的登记再也无人释放，通知常驻
 * 只在后台线程调用，锁内的通知 Binder 不会拖住主线程
 */
internal class UidtNotificationOwners(
    private val acquireOwner: (String) -> Unit,
    private val releaseOwner: (String) -> Unit
) {
    private val lock = Any()
    private val owners = linkedSetOf<String>()

    fun acquire(owner: String) {
        synchronized(lock) {
            owners += owner
            acquireOwner(owner)
        }
    }

    fun release(owner: String) {
        synchronized(lock) {
            if (!owners.remove(owner)) return
            releaseOwner(owner)
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            val released = owners.toList()
            owners.clear()
            released.forEach(releaseOwner)
        }
    }
}

private const val UIDT_RUN_TAG = "NERI-DownloadUidt"
