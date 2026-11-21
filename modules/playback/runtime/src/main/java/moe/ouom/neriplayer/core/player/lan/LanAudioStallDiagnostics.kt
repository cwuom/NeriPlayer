package moe.ouom.neriplayer.core.player.lan

internal class LanAudioStallDiagnostics {
    var rebuffers = 0
        private set
    var underruns = 0
        private set
    private var wasPlaying = false

    fun resetPlaybackTransition() {
        wasPlaying = false
    }

    fun reportRebuffer(playing: Boolean, playWhenReady: Boolean, buffering: Boolean): Boolean {
        val rebuffering = wasPlaying && playWhenReady && buffering
        wasPlaying = playing
        if (!rebuffering) return false
        rebuffers++
        return shouldLog(rebuffers)
    }

    fun reportUnderrun(): Boolean {
        underruns++
        return shouldLog(underruns)
    }

    // 连续欠载时保留完整计数，日志只记录第 1、2、4、8 次及后续倍增点
    private fun shouldLog(count: Int): Boolean = count.countOneBits() == 1
}
