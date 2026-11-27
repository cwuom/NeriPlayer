package moe.ouom.neriplayer.lanltw.core

/** Reject truncated previews and materially different versions before reporting loaded. */
object LanAudioDuration {
    fun matches(expectedMs: Long, actualMs: Long): Boolean {
        if (actualMs <= 0) return false
        if (expectedMs <= 0) return true
        return kotlin.math.abs(actualMs.toDouble() - expectedMs) <= maxOf(1000.0, expectedMs * .01)
    }
}
