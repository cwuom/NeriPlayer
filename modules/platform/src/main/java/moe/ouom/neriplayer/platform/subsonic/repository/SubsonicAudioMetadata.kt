package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.deriveCodecLabel
import org.json.JSONObject

internal data class SubsonicAudioMetadata(val info: PlaybackAudioInfo, val size: Long?) {
    val representation: String get() = listOf("subsonic", "raw", "v1", info.mimeType,
        info.bitrateKbps, info.sampleRateHz, info.bitDepth, size).joinToString(":")
}

internal fun subsonicAudioMetadata(json: JSONObject): SubsonicAudioMetadata {
    val suffix = json.optString("suffix").lowercase()
    val declaredType = json.optString("contentType").substringBefore(';').trim()
        .takeIf { it.startsWith("audio/") }
    val mimeType = declaredType ?: when (suffix) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        else -> null
    }
    return SubsonicAudioMetadata(
        info = PlaybackAudioInfo(
            source = PlaybackAudioSource.SUBSONIC,
            qualityKey = "raw",
            mimeType = mimeType,
            // MP4/M4A is a container: it can hold AAC or ALAC. Do not guess the codec.
            codecLabel = when {
                suffix == "opus" -> "OPUS"
                mimeType in setOf("audio/mp4", "audio/m4a", "audio/x-m4a") -> "M4A"
                else -> deriveCodecLabel(mimeType)
            },
            bitrateKbps = json.optInt("bitRate").takeIf { it > 0 },
            sampleRateHz = json.optInt("samplingRate").takeIf { it > 0 },
            bitDepth = json.optInt("bitDepth").takeIf { it > 0 },
            channelCount = json.optInt("channelCount").takeIf { it > 0 }
        ),
        size = json.optLong("size").takeIf { it > 0L }
    )
}
