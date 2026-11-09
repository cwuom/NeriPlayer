package moe.ouom.neriplayer.lanltw.core

/** Per-member reported timing. Invalid reports do not overwrite the last usable value. */
class LanPeerTiming {
    var rtt = 0.0
        private set
    var compensation = 0.0
        private set
    fun reportRtt(value: Double?) {
        if (value == null) return
        if (!value.isFinite() || value !in 0.0..10000.0) return
        rtt = if (rtt == 0.0) value else rtt * .8 + value * .2
    }
    fun reportCompensation(value: Double?) {
        if (value == null || !value.isFinite()) return
        compensation = value.coerceIn(0.0, 5000.0)
    }
}
