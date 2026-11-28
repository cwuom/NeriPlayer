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

private const val UIDT_RUN_TAG = "NERI-DownloadUidt"
