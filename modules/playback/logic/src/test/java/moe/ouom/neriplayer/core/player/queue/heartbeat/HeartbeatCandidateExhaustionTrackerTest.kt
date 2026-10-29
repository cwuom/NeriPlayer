package moe.ouom.neriplayer.core.player.queue.heartbeat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「候选耗尽」判定测试（任务书 §四 的 `heartbeat_candidates_insufficient` 提示依据）。
 *
 * 核心语义：
 * - 同一首歌连续失败达到 [HeartbeatCandidateExhaustionTracker.MAX_CONSECUTIVE_NO_CANDIDATE] 才判定耗尽；
 * - 换首歌失败计数重新开始，不会因为一首难搞的歌把整个会话判死；
 * - 任一次成功立刻解除耗尽（本地推荐仍在工作就不能说「候选不足」）；
 * - [HeartbeatCandidateExhaustionTracker.reset] 供开启/关闭心动模式时切换会话。
 */
class HeartbeatCandidateExhaustionTrackerTest {

    private val tracker = HeartbeatCandidateExhaustionTracker()

    @Test
    fun `starts not exhausted`() {
        assertFalse(tracker.isExhausted)
        assertFalse(tracker.isExhaustedFlow.value)
    }

    @Test
    fun `fewer than the threshold failures do not exhaust`() {
        tracker.onNoCandidate("netease:1")
        tracker.onNoCandidate("netease:1")

        assertFalse(tracker.isExhausted)
    }

    @Test
    fun `same song failing the threshold times exhausts`() {
        repeat(HeartbeatCandidateExhaustionTracker.MAX_CONSECUTIVE_NO_CANDIDATE) {
            tracker.onNoCandidate("netease:1")
        }

        assertTrue(tracker.isExhausted)
        assertTrue(tracker.isExhaustedFlow.value)
    }

    @Test
    fun `failures across different songs do not accumulate`() {
        tracker.onNoCandidate("netease:1")
        tracker.onNoCandidate("netease:2")
        tracker.onNoCandidate("netease:3")

        assertFalse(tracker.isExhausted)
    }

    @Test
    fun `a single success clears exhausted state`() {
        repeat(HeartbeatCandidateExhaustionTracker.MAX_CONSECUTIVE_NO_CANDIDATE) {
            tracker.onNoCandidate("netease:1")
        }
        assertTrue(tracker.isExhausted)

        tracker.onCandidate()

        assertFalse(tracker.isExhausted)
    }

    @Test
    fun `a success in the middle prevents reaching the threshold`() {
        tracker.onNoCandidate("netease:1")
        tracker.onNoCandidate("netease:1")
        tracker.onCandidate()
        tracker.onNoCandidate("netease:1")
        tracker.onNoCandidate("netease:1")

        assertFalse(tracker.isExhausted)
    }

    @Test
    fun `reset clears exhausted state and counters`() {
        repeat(HeartbeatCandidateExhaustionTracker.MAX_CONSECUTIVE_NO_CANDIDATE) {
            tracker.onNoCandidate("netease:1")
        }
        assertTrue(tracker.isExhausted)

        tracker.reset()

        assertFalse(tracker.isExhausted)
        // 计数也一并清零：重置后同一首歌要重新失败满阈值才会再次耗尽
        tracker.onNoCandidate("netease:1")
        tracker.onNoCandidate("netease:1")
        assertFalse(tracker.isExhausted)
    }

    @Test
    fun `custom threshold is honored`() {
        val strict = HeartbeatCandidateExhaustionTracker(maxConsecutiveNoCandidate = 2)

        strict.onNoCandidate("netease:1")
        assertFalse(strict.isExhausted)
        strict.onNoCandidate("netease:1")
        assertTrue(strict.isExhausted)
    }
}
