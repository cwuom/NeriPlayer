package moe.ouom.neriplayer.core.player.dlna.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 在 GENA 订阅过期前续订。
 *
 * 渲染器给的 TIMEOUT 通常是 300 秒，不续订的话 5 分钟后事件就停了，只能靠轮询发现状态变化。
 * 续订失败（SID 已失效、渲染器重启）时停止，后续仍由轮询兜底。
 */
internal class DlnaGenaRenewalScheduler(
    private val scope: CoroutineScope,
    private val renew: (eventSubUrl: String, sid: String, timeoutSeconds: Int) -> Result<Int>,
    private val onRenewFailed: (Throwable) -> Unit = {}
) {
    private var job: Job? = null

    @Synchronized
    fun start(eventSubUrl: String, sid: String, timeoutSeconds: Int, isCurrent: () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            var granted = timeoutSeconds
            while (isActive) {
                delay(renewDelayMs(granted))
                if (!isCurrent()) return@launch
                granted = renew(eventSubUrl, sid, granted).getOrElse {
                    onRenewFailed(it)
                    return@launch
                }
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val MIN_RENEW_DELAY_MS = 15_000L
        private const val MAX_RENEW_DELAY_MS = 30L * 60L * 1000L

        /** 在剩余 1/4 时长时续订，给网络往返留余量 */
        fun renewDelayMs(timeoutSeconds: Int): Long =
            (timeoutSeconds.coerceAtLeast(1) * 1000L * 3L / 4L).coerceIn(MIN_RENEW_DELAY_MS, MAX_RENEW_DELAY_MS)
    }
}
