package moe.ouom.neriplayer.lanltw.core

internal class LanPeerLiveness(private val silenceMs: Double) {
    enum class Status { HEALTHY, PROBE, EXPIRED }

    private var probeAt: Double? = null
    private var checkedAt = Double.NEGATIVE_INFINITY

    fun check(now: Double, lastReceivedAt: Double): Status {
        val missedChecks = now - checkedAt > RESPONSE_WINDOW_MS
        checkedAt = now
        if (now - lastReceivedAt <= silenceMs) {
            probeAt = null
            return Status.HEALTHY
        }
        val sentAt = probeAt
        // 本机刚恢复调度时重新给对端一次应答机会
        if (sentAt == null || missedChecks) {
            probeAt = now
            return Status.PROBE
        }
        return if (now - sentAt >= RESPONSE_WINDOW_MS) Status.EXPIRED else Status.HEALTHY
    }

    private companion object {
        const val RESPONSE_WINDOW_MS = 5_000.0
    }
}
