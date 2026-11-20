package moe.ouom.neriplayer.data.model.playback.dlna

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

enum class DlnaStreamMode {
    ORIGINAL_FLAC,
    LOSSLESS_WAV,
    LOSSLESS_WAV_ID3,
    ADAPTIVE_DIRECT
}

enum class DlnaTransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING,
    NO_MEDIA_PRESENT,
    UNKNOWN
}

enum class DlnaConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class DlnaDeviceOptions(
    val preferOriginalFlac: Boolean = true,
    val wavFallbackEnabled: Boolean = true,
    val wavEmbedId3Enabled: Boolean = false,
    val strictLosslessMode: Boolean = false
)

data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val location: String,
    val host: String,
    val manufacturer: String = "",
    val modelName: String = "",
    val avTransportControlUrl: String = "",
    val avTransportEventSubUrl: String = "",
    val renderingControlUrl: String = "",
    val renderingEventSubUrl: String = "",
    val connectionManagerUrl: String = "",
    val sinkProtocolInfo: String = "",
    val supportsFlac: Boolean = true,
    val supportsWav: Boolean = true,
    val supportsSetNextUri: Boolean = false,
    val online: Boolean = true,
    val isManual: Boolean = false
) {
    val isLikelyShanlingEm7: Boolean
        get() = friendlyName.contains("EM7", ignoreCase = true) ||
            modelName.contains("EM7", ignoreCase = true) ||
            manufacturer.contains("Shanling", ignoreCase = true)
}

data class DlnaAudioQualityReport(
    val songKey: String = "",
    val source: PlaybackAudioSource = PlaybackAudioSource.LOCAL,
    val resolvedFormat: String = "",
    val mimeType: String = "",
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val bitrateKbps: Int? = null,
    val deliveryMode: DlnaStreamMode = DlnaStreamMode.ORIGINAL_FLAC,
    val degraded: Boolean = false,
    val degradationReason: String? = null,
    val strictLosslessBlocked: Boolean = false,
    val blockReason: String? = null,
    val sourceSha256: String? = null,
    val totalBytes: Long = 0L,
    val headerPrefixBytes: Int = 0
)

data class DlnaPlaybackSnapshot(
    val generationId: Long,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int = 0,
    val durationMs: Long = 0L,
    val streamPath: String,
    val coverPath: String? = null,
    val contentType: String,
    val protocolInfo: String,
    val totalBytes: Long,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val streamMode: DlnaStreamMode
)

data class DlnaCastState(
    val initialized: Boolean = false,
    val isDiscovering: Boolean = false,
    val activeDevice: DlnaDevice? = null,
    val connectionStatus: DlnaConnectionStatus = DlnaConnectionStatus.DISCONNECTED,
    val transportState: DlnaTransportState = DlnaTransportState.UNKNOWN,
    val discoveredDevices: List<DlnaDevice> = emptyList(),
    val deviceOptionsByUdn: Map<String, DlnaDeviceOptions> = emptyMap(),
    val remotePositionMs: Long = 0L,
    val remoteDurationMs: Long = 0L,
    val remoteVolume: Int? = null,
    val volumeSupported: Boolean = false,
    val currentSnapshot: DlnaPlaybackSnapshot? = null,
    val qualityReport: DlnaAudioQualityReport? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val currentGenerationId: Long = 0L
) {
    val isCastingActive: Boolean
        get() = activeDevice != null && connectionStatus != DlnaConnectionStatus.DISCONNECTED

    fun optionsFor(udn: String): DlnaDeviceOptions =
        deviceOptionsByUdn[udn] ?: DlnaDeviceOptions()
}
