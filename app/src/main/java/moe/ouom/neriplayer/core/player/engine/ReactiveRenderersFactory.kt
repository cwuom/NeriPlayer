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


import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import moe.ouom.neriplayer.core.player.usb.sink.UsbExclusiveAudioSink
import moe.ouom.neriplayer.core.player.effects.AudioReactive

/**
 * 自定义 RenderersFactory：
 * - 注入 TeeAudioProcessor 将 PCM 能量送入 AudioReactive，供可视化/背景特效使用
 * - FFmpeg renderer 交给 Media3 扩展模式注册，避免抢在平台解码器前面
 */
@UnstableApi
class ReactiveRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val balance = StereoBalanceAudioProcessor()
        val tee = TeeAudioProcessor(AudioReactive.teeSink)
        val fallbackSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(balance, tee))
            .setEnableFloatOutput(false)
            // 优先使用 Media3 的音频处理链，避免部分设备在极低倍速下
            // 走平台 AudioTrack PlaybackParams 时出现明显电音/颗粒化失真
            .setEnableAudioTrackPlaybackParams(false)
            .build()
        return UsbExclusiveAudioSink(context.applicationContext, fallbackSink)
    }
}
