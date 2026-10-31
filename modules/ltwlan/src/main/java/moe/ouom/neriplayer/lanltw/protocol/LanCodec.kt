package moe.ouom.neriplayer.lanltw.protocol

import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.model.lan.LanMessage

object LanCodec {
    const val MAX_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    fun encode(message: LanMessage): String = json.encodeToString(LanMessage.serializer(), message)
    fun decode(text: String): LanMessage? {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return runCatching { json.decodeFromString(LanMessage.serializer(), text) }.getOrNull()?.takeIf(::finite)
    }
    private fun finite(m: LanMessage): Boolean {
        val numbers = listOf(m.t0, m.t1, m.t2, m.clientRTT, m.clientCompensationMs, m.clientNudgeMs, m.trackTimeSeconds, m.serverTimeToExecute)
        return numbers.all { it == null || it.isFinite() } && (m.volume?.isFinite() != false)
    }
    fun validProbe(m: LanMessage): Boolean = m.t0 != null && m.probeGroupId != null && m.probeGroupIndex in 0..1
}
