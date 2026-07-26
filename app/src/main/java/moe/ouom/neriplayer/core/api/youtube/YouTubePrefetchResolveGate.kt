package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * 预取解析的排队闸门, 允许被按需请求中途提走
 *
 * 预取解析是投机的, 挤在一条队里慢慢跑没关系; 但队列窗口一铺开就有六七个排在前面,
 * 用户这时点到其中一首, 那条解析已经卡在闸门上, 只能陪着整队跑完.
 * 所以等待名额和等待提升要同时进行, 谁先到就走谁
 */
internal class YouTubePrefetchResolveGate(permits: Int) {

    private val semaphore = Semaphore(permits)

    /**
     * [promotion] 完成即表示这条解析已被按需请求认领, 立刻放行不再等名额
     */
    suspend fun <T> withPrefetchSlot(promotion: Deferred<Unit>, block: suspend () -> T): T {
        if (promotion.isCompleted) {
            return block()
        }
        var holdsPermit = false
        coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            val acquisition = launch {
                semaphore.acquire()
                // 提升先到时名额已经没人要了, 必须原样还回去
                if (!outcome.complete(true)) {
                    semaphore.release()
                }
            }
            val watcher = launch {
                promotion.await()
                outcome.complete(false)
            }
            holdsPermit = outcome.await()
            acquisition.cancel()
            watcher.cancel()
        }
        return try {
            block()
        } finally {
            if (holdsPermit) {
                semaphore.release()
            }
        }
    }

    internal fun availablePermitsForTest(): Int = semaphore.availablePermits
}
