package moe.ouom.neriplayer.lanltw.core

import kotlin.math.abs
import moe.ouom.neriplayer.data.model.lan.LanMessage

// 移植自 BeatSync 94c38ab 的 ntp.ts，MIT 许可证见 resources/beatsync/LICENSE
class LanNtpEstimator {
    data class Estimate(val offset: Double, val rtt: Double, val synced: Boolean)

    private data class Probe(val group: Int, val sent: Double, val arrived: Double, val rtt: Double, val offset: Double) {
        fun finiteMeasurement() = rtt.isFinite() && offset.isFinite()

        fun matchesGap(second: Probe): Boolean {
            val clientGap = second.sent - sent
            val serverGap = second.arrived - arrived
            return abs(serverGap - clientGap) <= GAP_TOLERANCE_MS
        }
    }

    private val lock = Any()
    private val samples = ArrayDeque<Probe>(WINDOW_SIZE)
    private var pending: Probe? = null
    private var closed = false

    suspend fun reset() = synchronized(lock) { clearSamples() }

    suspend fun close() = synchronized(lock) {
        closed = true
        clearSamples()
    }

    suspend fun response(message: LanMessage, receivedAt: Double): Estimate? = synchronized(lock) {
        if (closed) return@synchronized null
        val probe = measure(message, receivedAt) ?: return@synchronized null
        when (message.probeGroupIndex) {
            0 -> {
                pending = probe
                null
            }
            1 -> finishPair(probe)
            else -> null
        }
    }

    private fun measure(message: LanMessage, receivedAt: Double): Probe? {
        val group = message.probeGroupId ?: return null
        val t0 = message.t0 ?: return null
        val t1 = message.t1 ?: return null
        val t2 = message.t2 ?: return null
        if (t2 < t1) return null
        val rtt = (receivedAt - t0) - (t2 - t1)
        val offset = ((t1 - t0) + (t2 - receivedAt)) / 2
        return Probe(group, t0, t1, rtt, offset).takeIf(Probe::finiteMeasurement)
    }

    private fun finishPair(second: Probe): Estimate? {
        val first = pending ?: return null
        if (first.group != second.group) return null
        pending = null
        if (!first.matchesGap(second)) return null
        val best = if (first.rtt <= second.rtt) first else second
        if (best.rtt < 0) return null
        return updateWindow(best)
    }

    private fun updateWindow(best: Probe): Estimate {
        if (samples.size == WINDOW_SIZE) samples.removeFirst()
        samples.addLast(best)
        return Estimate(samples.minBy { it.rtt }.offset, meanRtt(), samples.size == WINDOW_SIZE)
    }

    private fun meanRtt(): Double {
        val sum = samples.sumOf { it.rtt }
        if (sum.isFinite()) return sum / samples.size
        // 极端的有限输入可能在求和时溢出，增量均值仍在样本范围内
        return samples.foldIndexed(0.0) { index, mean, probe -> mean + (probe.rtt - mean) / (index + 1) }
    }

    private fun clearSamples() {
        pending = null
        samples.clear()
    }

    private companion object {
        const val WINDOW_SIZE = 16
        const val GAP_TOLERANCE_MS = 5.0
    }
}
