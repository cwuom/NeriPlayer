package moe.ouom.neriplayer.lanltw.core

/** BeatSync 94c38ab config.ts / global.tsx, with explicit input clock and units. */
object LanSchedule {
    fun executionDelay(rtt: Double, compensation: Double, extra: Double = 0.0): Double =
        maxOf((rtt * 1.5 + 200).coerceIn(400.0, 3000.0), compensation + 200) + extra
    fun position(anchor: Double, at: Double, now: Double): Double = (anchor + (now - at) / 1000).coerceAtLeast(0.0)
    data class Plan(val delayMs: Long, val positionSeconds: Double)
    fun play(now: Double, offset: Double, nudge: Int, lead: Double, target: Double, position: Double): Plan {
        val serverNow = now + offset
        val raw = (target - serverNow).coerceAtLeast(0.0)
        val wait = ((target - serverNow - nudge).coerceAtLeast(0.0) - lead).coerceAtLeast(0.0)
        if (wait >= 50) return Plan(wait.toLong(), position)
        if (raw >= 50) return Plan(0, position)
        val retry = (50 - raw + 200).coerceAtMost(2000.0)
        return Plan(retry.toLong(), (position + (serverNow - target + retry) / 1000).coerceAtLeast(0.0))
    }
    fun pauseDelay(now: Double, offset: Double, nudge: Int, lead: Double, target: Double): Long =
        ((target - now - offset - nudge).coerceAtLeast(0.0) - lead).coerceAtLeast(0.0).toLong()
}
