package moe.ouom.neriplayer.lanltw.playback

import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.core.LanNtpEstimator

/** Absolute deadlines are consumed by the audio engine before the deadline, not a UI timer. */
interface LanScheduledAudioPort {
    fun audioDiagnostics(): String = "[]"
    val outputLatencyMs: Double
    suspend fun initializeAudio()
    suspend fun resetAudioClock()
    suspend fun receiveProbe(message: LanMessage, receivedAt: Double): LanNtpEstimator.Estimate?
    suspend fun scheduleAudio(message: LanMessage, offsetMs: Double, nudgeMs: Int): Boolean
    fun cancelAudioSchedule()
    fun changeAudioNudge(value: Int, delta: Int)
}
