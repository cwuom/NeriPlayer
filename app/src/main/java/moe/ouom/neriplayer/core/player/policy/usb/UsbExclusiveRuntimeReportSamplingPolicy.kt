package moe.ouom.neriplayer.core.player.policy.usb

internal object UsbExclusiveRuntimeReportSamplingPolicy {
    fun shouldSampleFullRuntimeReport(
        nowMs: Long,
        lastSampleAtMs: Long,
        intervalMs: Long
    ): Boolean {
        if (intervalMs <= 0L) return true
        if (lastSampleAtMs <= 0L) return true
        return nowMs - lastSampleAtMs >= intervalMs
    }
}
