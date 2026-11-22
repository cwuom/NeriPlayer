package moe.ouom.neriplayer.core.player.lan.timing

import kotlin.math.abs
import kotlin.math.roundToLong

internal data class LanPcmRoute(
    val id: Int, val rate: Int, val bufferFrames: Long, val encoding: Int, val channelMask: Int, val resuming: Boolean
)

internal class LanStartLatency {
    private data class Samples(val previous: Double?, val latest: Double)
    private val routes = mutableMapOf<LanPcmRoute, Samples>()

    fun record(route: LanPcmRoute, latencyMs: Double) {
        if (latencyMs !in 0.0..250.0) return
        routes[route] = Samples(routes[route]?.latest, latencyMs)
    }

    fun leadMs(route: LanPcmRoute?): Long? {
        val samples = routes[route] ?: return null
        val previous = samples.previous ?: return null
        if (abs(samples.latest - previous) > 12.0) return null
        return ((samples.latest + previous) / 2).roundToLong()
    }

    fun waitMs(remainingMs: Long, ready: Boolean, route: LanPcmRoute?): Long {
        val advance = if (ready) leadMs(route) ?: 0L else 0L
        return (remainingMs - advance).coerceAtLeast(0)
    }
}

internal data class LanPcmStamp(val frame: Long, val nanoTime: Long) {
    fun follows(previous: LanPcmStamp, rate: Int): Boolean {
        val elapsed = (nanoTime - previous.nanoTime) / 1e6
        val audio = (frame - previous.frame) * 1000.0 / rate
        return elapsed in 1.0..500.0 && audio > 0 && abs(audio - elapsed) <= 3.0 + elapsed * 0.02
    }

    fun startupLatencyMs(startFrame: Long, rate: Int, playNanos: Long): Double? {
        val framesPlayed = frame - startFrame
        if (framesPlayed <= 0 || nanoTime - playNanos !in 1..1_000_000_000L) return null
        return ((nanoTime - playNanos) / 1e6 - framesPlayed * 1000.0 / rate).takeIf { it in 0.0..250.0 }
    }

    fun elapsedAtOutputUs(outputUs: Long, rate: Int, nowNanos: Long, elapsedNanos: Long): Double? {
        if (nowNanos - nanoTime !in -20_000_000L..250_000_000L) return null
        val distanceMs = outputUs / 1000.0 - frame * 1000.0 / rate
        if (abs(distanceMs) > 250.0) return null
        return (elapsedNanos + (nanoTime - nowNanos)) / 1e6 + distanceMs
    }
}
