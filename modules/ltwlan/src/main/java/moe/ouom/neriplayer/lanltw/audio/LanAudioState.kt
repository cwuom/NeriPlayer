package moe.ouom.neriplayer.lanltw.audio

import kotlinx.serialization.json.*

data class LanAudioState(
    val playing: Boolean = false, val positionSeconds: Double = 0.0, val durationSeconds: Double = 0.0,
    val outputLatencyMs: Double = 0.0, val clockRoundTripMs: Double = 0.0, val contextState: String = "suspended", val sampleRate: Int = 48000
) {
    companion object {
        fun parse(value: JsonObject) = LanAudioState(
            value["playing"]?.jsonPrimitive?.booleanOrNull ?: false,
            value["positionSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            value["durationSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            value["outputLatencyMs"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            value["clockRoundTripMs"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            value["contextState"]?.jsonPrimitive?.contentOrNull ?: "suspended",
            value["sampleRate"]?.jsonPrimitive?.intOrNull ?: 48000)
    }
}
