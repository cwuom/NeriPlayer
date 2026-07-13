@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package moe.ouom.neriplayer.core.player.effects

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
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从 ExoPlayer PCM 管线中“分流”样本，计算音量 level 与鼓点脉冲 beat
 */
object AudioReactive {
    @Volatile
    private var enabledState = false

    @Volatile
    internal var onEnabledChanged: ((Boolean) -> Unit)? = null

    var enabled: Boolean
        get() = enabledState
        set(value) {
            if (enabledState == value) return
            enabledState = value
            onEnabledChanged?.invoke(value)
            if (!value) {
                _level.value = 0f
                _beat.value = 0f
            }
        }

    private const val MIN_BEAT_GAP_NS = 120_000_000L
    private const val BEAT_DECAY_REFERENCE_NS = 16_666_667L
    private const val BEAT_DECAY_PER_REFERENCE = 0.90
    private const val EPS = 1e-9

    private var encoding: Int = C.ENCODING_PCM_16BIT
    private var channels: Int = 2
    private var sampleRate: Int = 44100

    // 能量包络与均值
    private var emaFast = 0.0
    private var emaSlow = 0.0
    private var noiseEma = 0.0
    private var lastBeatNs = 0L
    private var lastBeatUpdateNs = 0L

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
            lastBeatNs = 0L
            lastBeatUpdateNs = 0L
            _level.value = 0f
            _beat.value = 0f
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            handlePcmBuffer(buffer)
        }
    }

    fun handlePcmBuffer(
        buffer: ByteBuffer,
        effectiveVolume: Float = 1f
    ) {
        handlePcmBuffer(
            buffer = buffer,
            effectiveVolume = effectiveVolume,
            nowNs = System.nanoTime()
        )
    }

    internal fun handlePcmBuffer(
        buffer: ByteBuffer,
        effectiveVolume: Float,
        nowNs: Long
    ) {
        if (!enabled || !buffer.hasRemaining()) return

        val rawLevel = when (encoding) {
            C.ENCODING_PCM_8BIT -> rms8(buffer)
            C.ENCODING_PCM_FLOAT -> rmsFloat(buffer)
            C.ENCODING_PCM_16BIT -> rms16(buffer, ByteOrder.LITTLE_ENDIAN)
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> rms16(buffer, ByteOrder.BIG_ENDIAN)
            C.ENCODING_PCM_24BIT -> rms24(buffer, bigEndian = false)
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> rms24(buffer, bigEndian = true)
            C.ENCODING_PCM_32BIT -> rms32(buffer, ByteOrder.LITTLE_ENDIAN)
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> rms32(buffer, ByteOrder.BIG_ENDIAN)
            else -> rms16(buffer, ByteOrder.LITTLE_ENDIAN)
        }
        val gain = effectiveVolume.coerceIn(0f, 1f).toDouble()
        val lvl = (rawLevel * gain).coerceIn(0.0, 1.0)

        val aFast = 0.5   // 攻速
        val aSlow = 0.05  // 释速
        emaFast = aFast * lvl + (1 - aFast) * emaFast
        emaSlow = aSlow * lvl + (1 - aSlow) * emaSlow

        val delta = max(0.0, emaFast - emaSlow)
        noiseEma = 0.02 * delta + 0.98 * noiseEma // 自适应噪声地板
        val threshold = 3.0 * (noiseEma + EPS)

        var newBeat = false
        if (delta > threshold && nowNs - lastBeatNs > MIN_BEAT_GAP_NS) {
            lastBeatNs = nowNs
            lastBeatUpdateNs = nowNs
            _beat.value = 1f
            newBeat = true
        } else {
            decayBeat(nowNs)
        }

        val perceptual = sqrt(lvl).toFloat()
        _level.value = if (newBeat) max(perceptual, min(1f, perceptual + 0.08f)) else perceptual
    }

    internal fun resetForTest() {
        enabledState = false
        onEnabledChanged = null
        encoding = C.ENCODING_PCM_16BIT
        channels = 2
        sampleRate = 44100
        emaFast = 0.0
        emaSlow = 0.0
        noiseEma = 0.0
        lastBeatNs = 0L
        lastBeatUpdateNs = 0L
        _level.value = 0f
        _beat.value = 0f
    }

    private fun decayBeat(nowNs: Long) {
        if (lastBeatUpdateNs == 0L) {
            lastBeatUpdateNs = nowNs
            return
        }
        val elapsedNs = (nowNs - lastBeatUpdateNs).coerceAtLeast(0L)
        lastBeatUpdateNs = nowNs
        if (elapsedNs == 0L) return
        val references = elapsedNs.toDouble() / BEAT_DECAY_REFERENCE_NS.toDouble()
        _beat.value *= BEAT_DECAY_PER_REFERENCE.pow(references).toFloat()
    }

    private fun rms8(buf: ByteBuffer): Double {
        val dup = buf.duplicate()
        var sum = 0.0
        var count = 0
        while (dup.remaining() >= 1) {
            val f = ((dup.get().toInt() and 0xFF) - 128) / 128.0
            sum += f * f
            count++
        }
        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    private fun rms16(buf: ByteBuffer, byteOrder: ByteOrder): Double {
        val dup = buf.duplicate().order(byteOrder)
        var sum = 0.0
        var count = 0
        while (dup.remaining() >= 2) {
            val s = dup.short.toInt()
            val f = s / 32768.0
            sum += f * f
            count++
        }
        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    private fun rms24(buf: ByteBuffer, bigEndian: Boolean): Double {
        val dup = buf.duplicate()
        var sum = 0.0
        var count = 0
        while (dup.remaining() >= 3) {
            val b0 = dup.get().toInt() and 0xFF
            val b1 = dup.get().toInt() and 0xFF
            val b2 = dup.get().toInt() and 0xFF
            val sample = if (bigEndian) {
                (b0 shl 16) or (b1 shl 8) or b2
            } else {
                b0 or (b1 shl 8) or (b2 shl 16)
            }
            val signedSample = if (sample and 0x800000 != 0) {
                sample or 0xFF000000.toInt()
            } else {
                sample
            }
            val f = signedSample / 8388608.0
            sum += f * f
            count++
        }
        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    private fun rms32(buf: ByteBuffer, byteOrder: ByteOrder): Double {
        val dup = buf.duplicate().order(byteOrder)
        var sum = 0.0
        var count = 0
        while (dup.remaining() >= 4) {
            val s = dup.int.toLong()
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
            val f = dup.float
            val normalized = if (f.isFinite()) f.coerceIn(-1f, 1f).toDouble() else 0.0
            sum += normalized * normalized
            count++
        }
        if (count == 0) return 0.0
        return sqrt(sum / count)
    }
}
