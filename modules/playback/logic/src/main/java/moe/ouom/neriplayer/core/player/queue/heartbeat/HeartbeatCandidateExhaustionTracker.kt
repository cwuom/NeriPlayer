package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 心动模式「候选耗尽」判定。
 *
 * 为什么需要它：任务书 §四 要求「候选池为空」时提示
 * 「心动模式候选歌曲不足，已回退到原有模式」，但没有告诉上层怎么判定。
 * 这里把判定规则收敛到一个纯状态类里，既供 runtime 调用，也便于单测。
 *
 * **判定规则（刻意从宽）**：同一首歌连续 [MAX_CONSECUTIVE_NO_CANDIDATE] 次
 * 拿不到候选，才判定为「候选耗尽」，之后沿用原有播放模式。
 *
 * 之所以不用「官方源熔断」直接判定：那只能说明官方不可用，
 * 本地推荐仍在工作，此时提示「候选不足」与事实矛盾。
 * 因此本类独立计数，只在**官方与本地两条路都拿不到歌**时才提示。
 *
 * 本类不发出任何通知，只暴露 [isExhaustedFlow]；由 runtime 决定何时读它、
 * 是否转成 UI 状态。线程安全：所有写操作以内置锁串行化。
 */
class HeartbeatCandidateExhaustionTracker(
    private val maxConsecutiveNoCandidate: Int = MAX_CONSECUTIVE_NO_CANDIDATE,
) {
    private val lock = Any()
    private val exhaustionState = MutableStateFlow(false)

    private var consecutiveNoCandidate = 0
    private var lastKey: String? = null

    /** 候选耗尽状态；true 表示本次会话已无法给出心动候选。 */
    val isExhaustedFlow: StateFlow<Boolean> = exhaustionState.asStateFlow()

    /** 当前是否已判定为候选耗尽。 */
    val isExhausted: Boolean
        get() = exhaustionState.value

    /**
     * 记录一次「没有拿到候选」。
     *
     * @param key 当前歌曲的身份键；同一首歌连续失败才累计到熔断。
     */
    fun onNoCandidate(key: String) {
        synchronized(lock) {
            if (lastKey == key) {
                consecutiveNoCandidate += 1
            } else {
                lastKey = key
                consecutiveNoCandidate = 1
            }
            if (consecutiveNoCandidate >= maxConsecutiveNoCandidate) {
                exhaustionState.value = true
            }
        }
    }

    /** 记录一次「成功拿到候选」，清零计数并解除耗尽状态。 */
    fun onCandidate() {
        synchronized(lock) {
            consecutiveNoCandidate = 0
            lastKey = null
            exhaustionState.value = false
        }
    }

    /** 开启/关闭心动模式时调用：会话切换，全部重新开始。 */
    fun reset() {
        synchronized(lock) {
            consecutiveNoCandidate = 0
            lastKey = null
            exhaustionState.value = false
        }
    }

    companion object {
        /**
         * 同一首歌连续多少次拿不到候选后判定为「候选耗尽」。
         *
         * 取 3 与官方源熔断阈值对齐：一次播完、一次跳过、一次失败重试之后
         * 仍无候选，才判定为耗尽，避免误报。
         */
        const val MAX_CONSECUTIVE_NO_CANDIDATE = 3
    }
}
