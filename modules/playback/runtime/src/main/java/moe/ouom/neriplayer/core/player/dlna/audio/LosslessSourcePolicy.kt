package moe.ouom.neriplayer.core.player.dlna.audio

import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode

/**
 * Pure policy governing lossless-first priority, adaptive direct streaming
 * across all platforms (Netease, Bilibili, YouTube Music, Local), optional
 * strict lossless blocking, and lossless WAV/ID3 fallback selection.
 */
object LosslessSourcePolicy {

    enum class BlockReasonKind {
        PREVIEW_CLIP,
        LOSSY_SOURCE,
        FLAC_UNSUPPORTED_WAV_DISABLED,
        ALAC_REQUIRES_WAV_FALLBACK
    }

    enum class DegradationKind {
        LOSSY_SOURCE,
        DOWNGRADED_TIER
    }

    sealed class DeliveryDecision {
        data class Deliver(
            val mode: DlnaStreamMode,
            val degraded: Boolean,
            val degradationKind: DegradationKind?,
            val degradationReason: String?,
            val sourceSummary: String,
            val resolvedFormat: String,
            val mimeType: String,
            val sampleRateHz: Int,
            val bitDepth: Int,
            val channelCount: Int
        ) : DeliveryDecision()

        data class Blocked(
            val kind: BlockReasonKind,
            val reason: String,
            val sourceSummary: String,
            val resolvedFormat: String,
            val mimeType: String,
            val sampleRateHz: Int?,
            val bitDepth: Int?
        ) : DeliveryDecision()
    }

    fun evaluate(
        inspected: FlacHeaderInspector.InspectedAudio,
        audioInfo: PlaybackAudioInfo?,
        isPreviewClip: Boolean,
        requestedLosslessQuality: Boolean,
        device: DlnaDevice,
        options: DlnaDeviceOptions
    ): DeliveryDecision {
        val resolvedFormat = resolveDisplayFormat(inspected, audioInfo)
        val sourceSummary = buildSourceSummary(resolvedFormat, audioInfo, inspected)
        val effectiveMime = inspected.mimeType.takeIf {
            inspected.format != FlacHeaderInspector.ContainerFormat.UNKNOWN
        } ?: normalizeAudioMime(audioInfo?.mimeType) ?: "application/octet-stream"
        val sampleRate = inspected.sampleRateHz ?: audioInfo?.sampleRateHz ?: 0
        val channels = inspected.channelCount ?: audioInfo?.channelCount ?: 2

        if (isPreviewClip && options.strictLosslessMode) {
            return DeliveryDecision.Blocked(
                kind = BlockReasonKind.PREVIEW_CLIP,
                reason = "Preview clip only ($sourceSummary); full lossless source unavailable.",
                sourceSummary = sourceSummary,
                resolvedFormat = resolvedFormat,
                mimeType = effectiveMime,
                sampleRateHz = sampleRate.takeIf { it > 0 },
                bitDepth = inspected.bitDepth ?: audioInfo?.bitDepth
            )
        }

        val isLosslessContainer = when {
            inspected.format != FlacHeaderInspector.ContainerFormat.UNKNOWN -> inspected.isLossless
            else -> isDeclaredLosslessMime(audioInfo?.mimeType)
        }

        if (!isLosslessContainer) {
            if (options.strictLosslessMode) {
                return DeliveryDecision.Blocked(
                    kind = BlockReasonKind.LOSSY_SOURCE,
                    reason = "Strict lossless mode blocked lossy source ($sourceSummary).",
                    sourceSummary = sourceSummary,
                    resolvedFormat = resolvedFormat,
                    mimeType = effectiveMime,
                    sampleRateHz = inspected.sampleRateHz ?: audioInfo?.sampleRateHz,
                    bitDepth = inspected.bitDepth ?: audioInfo?.bitDepth
                )
            }
            // Adaptive direct streaming: NEVER transcode lossy MP3/AAC/M4A/OGG/WebM into WAV or MP3,
            // and NEVER upsample or fabricate bit depth. Serve original bytes directly.
            return DeliveryDecision.Deliver(
                mode = DlnaStreamMode.ADAPTIVE_DIRECT,
                degraded = true,
                degradationKind = DegradationKind.LOSSY_SOURCE,
                degradationReason = "Actual source is $sourceSummary (adaptive original direct stream)",
                sourceSummary = sourceSummary,
                resolvedFormat = resolvedFormat,
                mimeType = effectiveMime,
                sampleRateHz = sampleRate,
                bitDepth = 0,
                channelCount = channels
            )
        }

        val losslessSampleRate = sampleRate.takeIf { it > 0 } ?: 44_100
        val losslessBitDepth = inspected.bitDepth ?: audioInfo?.bitDepth ?: 16

        val wavMode = if (options.wavEmbedId3Enabled) {
            DlnaStreamMode.LOSSLESS_WAV_ID3
        } else {
            DlnaStreamMode.LOSSLESS_WAV
        }

        val mode = when (inspected.format) {
            FlacHeaderInspector.ContainerFormat.WAV -> wavMode
            FlacHeaderInspector.ContainerFormat.FLAC -> {
                if (options.preferOriginalFlac && device.supportsFlac) {
                    DlnaStreamMode.ORIGINAL_FLAC
                } else if (options.wavFallbackEnabled && device.supportsWav) {
                    wavMode
                } else {
                    return DeliveryDecision.Blocked(
                        kind = BlockReasonKind.FLAC_UNSUPPORTED_WAV_DISABLED,
                        reason = "Renderer does not support FLAC and WAV fallback is disabled.",
                        sourceSummary = sourceSummary,
                        resolvedFormat = resolvedFormat,
                        mimeType = effectiveMime,
                        sampleRateHz = losslessSampleRate,
                        bitDepth = losslessBitDepth
                    )
                }
            }
            FlacHeaderInspector.ContainerFormat.ALAC_MP4 -> {
                if (options.wavFallbackEnabled && device.supportsWav) {
                    wavMode
                } else if (!options.strictLosslessMode) {
                    DlnaStreamMode.ADAPTIVE_DIRECT
                } else {
                    return DeliveryDecision.Blocked(
                        kind = BlockReasonKind.ALAC_REQUIRES_WAV_FALLBACK,
                        reason = "ALAC source requires WAV fallback enabled for DLNA delivery.",
                        sourceSummary = sourceSummary,
                        resolvedFormat = resolvedFormat,
                        mimeType = effectiveMime,
                        sampleRateHz = losslessSampleRate,
                        bitDepth = losslessBitDepth
                    )
                }
            }
            else -> {
                if (options.preferOriginalFlac && device.supportsFlac) {
                    DlnaStreamMode.ORIGINAL_FLAC
                } else if (options.wavFallbackEnabled && device.supportsWav) {
                    wavMode
                } else {
                    DlnaStreamMode.ORIGINAL_FLAC
                }
            }
        }

        val downgradedFromRequested = requestedLosslessQuality &&
            audioInfo?.source == PlaybackAudioSource.NETEASE &&
            audioInfo.qualityKey in setOf("standard", "higher", "exhigh")

        return DeliveryDecision.Deliver(
            mode = mode,
            degraded = downgradedFromRequested,
            degradationKind = if (downgradedFromRequested) DegradationKind.DOWNGRADED_TIER else null,
            degradationReason = if (downgradedFromRequested) {
                "Platform downgraded quality to ${audioInfo.qualityLabel ?: audioInfo.qualityKey}"
            } else {
                null
            },
            sourceSummary = sourceSummary,
            resolvedFormat = resolvedFormat,
            mimeType = if (mode == DlnaStreamMode.LOSSLESS_WAV || mode == DlnaStreamMode.LOSSLESS_WAV_ID3) {
                "audio/wav"
            } else {
                effectiveMime
            },
            sampleRateHz = losslessSampleRate,
            bitDepth = losslessBitDepth,
            channelCount = channels
        )
    }

    fun buildSourceSummary(
        resolvedFormat: String,
        audioInfo: PlaybackAudioInfo?,
        inspected: FlacHeaderInspector.InspectedAudio
    ): String {
        val parts = mutableListOf(resolvedFormat)
        audioInfo?.qualityLabel?.takeIf { it.isNotBlank() }?.let { parts += it }
        audioInfo?.bitrateKbps?.takeIf { it > 0 }?.let { parts += "$it kbps" }
        (inspected.sampleRateHz ?: audioInfo?.sampleRateHz)?.takeIf { it > 0 }?.let { hz ->
            val khz = if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz"
            parts += khz
        }
        if (inspected.isLossless) {
            (inspected.bitDepth ?: audioInfo?.bitDepth)?.takeIf { it > 0 }?.let { parts += "$it bit" }
        }
        return parts.distinct().joinToString(" · ")
    }

    private fun resolveDisplayFormat(
        inspected: FlacHeaderInspector.InspectedAudio,
        audioInfo: PlaybackAudioInfo?
    ): String = when (inspected.format) {
        FlacHeaderInspector.ContainerFormat.FLAC -> "FLAC"
        FlacHeaderInspector.ContainerFormat.WAV -> "WAV"
        FlacHeaderInspector.ContainerFormat.ALAC_MP4 -> "ALAC"
        FlacHeaderInspector.ContainerFormat.MP3 -> "MP3"
        FlacHeaderInspector.ContainerFormat.AAC_ADTS -> "AAC"
        FlacHeaderInspector.ContainerFormat.AAC_OR_MP4 -> audioInfo?.codecLabel?.takeIf { it.isNotBlank() } ?: "M4A/AAC"
        FlacHeaderInspector.ContainerFormat.OGG -> "OGG"
        FlacHeaderInspector.ContainerFormat.WEBM -> "WebM/OPUS"
        FlacHeaderInspector.ContainerFormat.UNKNOWN -> audioInfo?.codecLabel ?: "UNKNOWN"
    }

    private fun normalizeAudioMime(mimeType: String?): String? {
        val clean = mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (clean) {
            "audio/m4a" -> "audio/mp4"
            "audio/mp3" -> "audio/mpeg"
            else -> clean
        }
    }

    private fun isDeclaredLosslessMime(mimeType: String?): Boolean {
        val clean = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return clean in setOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/alac")
    }
}
