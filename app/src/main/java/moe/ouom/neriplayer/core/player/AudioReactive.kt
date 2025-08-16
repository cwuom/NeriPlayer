@file:OptIn(UnstableApi::class)

package moe.ouom.neriplayer.core.player

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
 * File: moe.ouom.neriplayer.core.player/AudioReactive
 * Updated: 2025/8/16
 */

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从 ExoPlayer PCM 管线中“分流”样本，计算音量 level 与鼓点脉冲 beat
 */
object AudioReactive {
    var enabled = true

    private const val MIN_BEAT_GAP_NS = 120_000_000L
    private const val DECAY_PER_CALL = 0.90f
    private const val EPS = 1e-9

    private var encoding: Int = C.ENCODING_PCM_16BIT
    private var channels: Int = 2
    private var sampleRate: Int = 44100

    // 能量包络与均值
    private var emaFast = 0.0
    private var emaSlow = 0.0
    private var noiseEma = 0.0
    private var lastBeatNs = 0L

    private val _level = MutableStateFlow(0f) // 0..1
    private val _beat  = MutableStateFlow(0f) // 0..1 带衰减
    val level: StateFlow<Float> = _level
    val beat:  StateFlow<Float> = _beat

    val teeSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this@AudioReactive.sampleRate = sampleRateHz
            this@AudioReactive.channels   = max(1, channelCount)
            this@AudioReactive.encoding   = encoding
            emaFast = 0.0; emaSlow = 0.0; noiseEma = 0.0
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!enabled || !buffer.hasRemaining()) return

            val lvl = when (encoding) {
                C.ENCODING_PCM_FLOAT -> rmsFloat(buffer)
                C.ENCODING_PCM_16BIT -> rms16(buffer)
                C.ENCODING_PCM_24BIT -> rms24(buffer)
                C.ENCODING_PCM_32BIT -> rms32(buffer)
                else -> rms16(buffer) // 默认回退到16位处理
            }

            // lvl ~ [0..1] 线性幅值


            val aFast = 0.5   // 攻速
            val aSlow = 0.05  // 释速
            emaFast = aFast * lvl + (1 - aFast) * emaFast
            emaSlow = aSlow * lvl + (1 - aSlow) * emaSlow

            // 正向能量增量
            val delta = max(0.0, emaFast - emaSlow)
            noiseEma = 0.02 * delta + 0.98 * noiseEma // 自适应噪声地板
            val threshold = 3.0 * (noiseEma + EPS)

            val now = System.nanoTime()
            var newBeat = false
            if (delta > threshold && now - lastBeatNs > MIN_BEAT_GAP_NS) {
                lastBeatNs = now
                _beat.value = 1f
                newBeat = true
            } else {
                _beat.value *= DECAY_PER_CALL
            }

            val perceptual = sqrt(min(1.0, max(0.0, lvl))).toFloat()
            _level.value = if (newBeat) max(perceptual, min(1f, perceptual + 0.08f)) else perceptual
        }

        private fun rms16(buf: ByteBuffer): Double {
            val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var count = 0
            while (dup.remaining() >= 2) {
                val s = dup.short.toInt() // -32768..32767
                val f = s / 32768.0
                sum += f * f
                count++
            }
            if (count == 0) return 0.0
            return sqrt(sum / count)
        }

        private fun rms24(buf: ByteBuffer): Double {
            val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var count = 0
            while (dup.remaining() >= 3) {
                // 将3个字节手动组合成一个24位有符号整数
                val sample = (dup.get().toInt() and 0xFF) or
                        ((dup.get().toInt() and 0xFF) shl 8) or
                        ((dup.get().toInt() and 0xFF) shl 16)
                // 符号位扩展
                val signedSample = if (sample and 0x800000 != 0) sample or 0xFF000000.toInt() else sample
                val f = signedSample / 8388608.0 // 2^23
                sum += f * f
                count++
            }
            if (count == 0) return 0.0
            return sqrt(sum / count)
        }

        private fun rms32(buf: ByteBuffer): Double {
            val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var count = 0
            while (dup.remaining() >= 4) {
                val s = dup.int.toLong() // -2147483648..2147483647
                val f = s / 2147483648.0
                sum += f * f
                count++
            }
            if (count == 0) return 0.0
            return sqrt(sum / count)
        }
        private fun rmsFloat(buf: ByteBuffer): Double {
            val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var count = 0
            while (dup.remaining() >= 4) {
                val f = dup.float.toDouble() // -1..1
                sum += f * f
                count++
            }
            if (count == 0) return 0.0
            return sqrt(sum / count)
        }
    }
}