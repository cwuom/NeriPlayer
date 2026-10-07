package moe.ouom.neriplayer.core.player.engine

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player/ReactiveRenderersFactory
 * Updated: 2025/8/16
 */


import moe.ouom.neriplayer.core.player.audio.processing.StereoBalanceAudioProcessor
import moe.ouom.neriplayer.core.player.audio.processing.VolumeNormalizationAudioProcessor
import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.effects.AudioEffectsAudioSink
import moe.ouom.neriplayer.core.player.usb.sink.UsbExclusiveAudioSink
import moe.ouom.neriplayer.core.player.audio.reactive.AudioReactive

/**
 * 自定义 RenderersFactory:
 * - 注入 TeeAudioProcessor 将 PCM 能量送入 AudioReactive, 供可视化/背景特效使用
 * - 仅对 FLAC/ALAC 优先使用内置 FFmpeg, 避免部分设备的平台解码器将有效比特流过早标记为结束,
 *   或像 c2.qti.alac.sw.decoder 那样声明支持却在送入数据时解码失败
 */
@UnstableApi
class ReactiveRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private var forceFfmpegPcm16Output = true

    init {
        val ffmpegPreferredMimeTypes = FFMPEG_PREFERRED_AUDIO_MIME_TYPES.filterTo(linkedSetOf()) { mimeType ->
            runCatching {
                FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType)
            }.getOrElse { error ->
                NPLogger.w(
                    "NERI-Player",
                    "FFmpeg $mimeType decoder probe failed; keep platform decoder",
                    error
                )
                false
            }
        }
        if (ffmpegPreferredMimeTypes.isNotEmpty()) {
            setMediaCodecSelector(
                FfmpegPreferredMediaCodecSelector(
                    delegate = MediaCodecSelector.DEFAULT,
                    ffmpegPreferredMimeTypes = ffmpegPreferredMimeTypes
                )
            )
            NPLogger.i(
                "NERI-Player",
                "Decoder policy: prefer bundled FFmpeg over platform MediaCodec for $ffmpegPreferredMimeTypes"
            )
        } else {
            NPLogger.w(
                "NERI-Player",
                "Bundled FFmpeg cannot decode FLAC or ALAC; keep platform MediaCodec"
            )
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        val ffmpegRendererIndex = out.indexOfFirst { it is FfmpegAudioRenderer }
        if (ffmpegRendererIndex < 0) return

        out[ffmpegRendererIndex] = FfmpegAudioRenderer(
            eventHandler,
            eventListener,
            FfmpegPcm16AudioSink(
                delegate = audioSink,
                shouldForcePcm16 = {
                    forceFfmpegPcm16Output && !PlayerManager.usbExclusivePlaybackEnabled
                }
            )
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        forceFfmpegPcm16Output = !enableFloatOutput
        val volumeNormalization = VolumeNormalizationAudioProcessor()
        val balance = StereoBalanceAudioProcessor()
        val tee = TeeAudioProcessor(AudioReactive.teeSink)
        val fallbackSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(volumeNormalization, balance, tee))
            .setEnableFloatOutput(enableFloatOutput)
            // 优先使用 Media3 的音频处理链, 避免部分设备在极低倍速下
            // 走平台 AudioTrack PlaybackParams 时出现明显电音/颗粒化失真
            .setEnableAudioOutputPlaybackParameters(false)
            .build()
        val usbSink = UsbExclusiveAudioSink(context.applicationContext, fallbackSink)
        return AudioEffectsAudioSink(usbSink, usbNativeOutputActive = usbSink::isNativeOutputActive)
    }
}

@UnstableApi
internal class FfmpegPcm16AudioSink(
    delegate: AudioSink,
    private val shouldForcePcm16: () -> Boolean
) : ForwardingAudioSink(delegate) {
    override fun supportsFormat(format: Format): Boolean {
        return (!shouldForcePcm16() || !format.isFloatPcm()) && super.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        return if (shouldForcePcm16() && format.isFloatPcm()) {
            SINK_FORMAT_UNSUPPORTED
        } else {
            super.getFormatSupport(format)
        }
    }

    private fun Format.isFloatPcm(): Boolean {
        return sampleMimeType == MimeTypes.AUDIO_RAW &&
            pcmEncoding == C.ENCODING_PCM_FLOAT
    }
}

internal val FFMPEG_PREFERRED_AUDIO_MIME_TYPES = listOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC)

@UnstableApi
internal class FfmpegPreferredMediaCodecSelector(
    private val delegate: MediaCodecSelector,
    private val ffmpegPreferredMimeTypes: Set<String>
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ) = if (ffmpegPreferredMimeTypes.any { it.equals(mimeType, ignoreCase = true) }) {
        emptyList()
    } else {
        delegate.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )
    }
}
