package moe.ouom.neriplayer.data.model.playback.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val RESPONSE_SAMPLE_RATE = 48_000.0
private const val GRAPHIC_BAND_Q = 1.41
private const val SHELF_Q = 0.707

/** 与 native neri_dsp_primitives.cpp 相同的 RBJ 设计，用于界面曲线和自动余量估算 */
internal data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
) {
    fun magnitudeDb(frequencyHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)
        val numerator = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cosW + 2.0 * b0 * b2 * cos2W
        val denominator = 1.0 + a1 * a1 + a2 * a2 + 2.0 * (a1 + a1 * a2) * cosW + 2.0 * a2 * cos2W
        if (numerator <= 0.0 || denominator <= 0.0) return -120.0
        return 10.0 * log10(numerator / denominator)
    }

    companion object {
        val Identity = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

internal fun designBiquad(
    type: ParametricEqBandType,
    sampleRate: Double,
    frequencyHz: Double,
    gainDb: Double,
    q: Double
): BiquadCoefficients {
    val frequency = frequencyHz.coerceIn(5.0, sampleRate * 0.45)
    val safeQ = q.coerceIn(0.1, 24.0)
    val w0 = 2.0 * PI * frequency / sampleRate
    val cosW = cos(w0)
    val alpha = sin(w0) / (2.0 * safeQ)
    val a = 10.0.pow(gainDb / 40.0)
    return when (type) {
        ParametricEqBandType.PEAK -> normalize(
            1.0 + alpha * a, -2.0 * cosW, 1.0 - alpha * a,
            1.0 + alpha / a, -2.0 * cosW, 1.0 - alpha / a
        )
        ParametricEqBandType.LOW_SHELF -> shelf(true, a, cosW, alpha)
        ParametricEqBandType.HIGH_SHELF -> shelf(false, a, cosW, alpha)
        ParametricEqBandType.LOW_PASS -> normalize(
            (1.0 - cosW) * 0.5, 1.0 - cosW, (1.0 - cosW) * 0.5, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha
        )
        ParametricEqBandType.HIGH_PASS -> normalize(
            (1.0 + cosW) * 0.5, -(1.0 + cosW), (1.0 + cosW) * 0.5, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha
        )
        ParametricEqBandType.NOTCH -> normalize(1.0, -2.0 * cosW, 1.0, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha)
        ParametricEqBandType.BAND_PASS -> normalize(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha)
    }
}

private fun shelf(low: Boolean, a: Double, cosW: Double, alpha: Double): BiquadCoefficients {
    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
    return if (low) {
        normalize(
            a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cosW),
            a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha),
            (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha,
            -2.0 * ((a - 1.0) + (a + 1.0) * cosW),
            (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
        )
    } else {
        normalize(
            a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW),
            a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha),
            (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha,
            2.0 * ((a - 1.0) - (a + 1.0) * cosW),
            (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
        )
    }
}

private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): BiquadCoefficients {
    if (a0 == 0.0 || !a0.isFinite()) return BiquadCoefficients.Identity
    return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

internal fun AudioEffectsSound.responseFilters(sampleRate: Double = RESPONSE_SAMPLE_RATE): List<BiquadCoefficients> {
    val filters = mutableListOf<BiquadCoefficients>()
    if (equalizerEnabled) {
        equalizerBandsDb.forEachIndexed { index, gain ->
            if (gain != 0f) {
                filters += designBiquad(
                    ParametricEqBandType.PEAK,
                    sampleRate,
                    AudioEffectsGraphicBandFrequenciesHz[index].toDouble(),
                    gain.toDouble(),
                    GRAPHIC_BAND_Q
                )
            }
        }
    }
    if (parametricEnabled) {
        parametricBands.filter { it.enabled }.forEach { band ->
            filters += designBiquad(
                ParametricEqBandType.fromStorageValue(band.type),
                sampleRate,
                band.frequencyHz.toDouble(),
                band.gainDb.toDouble(),
                band.q.toDouble()
            )
        }
    }
    if (bassDb != 0f) {
        filters += designBiquad(ParametricEqBandType.LOW_SHELF, sampleRate, bassFrequencyHz.toDouble(), bassDb.toDouble(), SHELF_Q)
    }
    if (trebleDb != 0f) {
        filters += designBiquad(
            ParametricEqBandType.HIGH_SHELF, sampleRate, trebleFrequencyHz.toDouble(), trebleDb.toDouble(), SHELF_Q
        )
    }
    return filters
}

/** 均衡器合成后的频响，单位 dB，包含前级增益 */
fun AudioEffectsSound.responseDb(frequencyHz: Float): Float {
    val filters = responseFilters()
    val total = filters.sumOf { it.magnitudeDb(frequencyHz.toDouble(), RESPONSE_SAMPLE_RATE) }
    return (total + preampDb).toFloat()
}

/** 20 Hz-20 kHz 对数等距采样，供界面绘制均衡曲线 */
fun AudioEffectsSound.responseCurve(points: Int = 96): List<Pair<Float, Float>> {
    val count = points.coerceAtLeast(2)
    val filters = responseFilters()
    val low = ln(20.0)
    val high = ln(20_000.0)
    return List(count) { index ->
        val frequency = exp(low + (high - low) * index / (count - 1))
        val gain = filters.sumOf { it.magnitudeDb(frequency, RESPONSE_SAMPLE_RATE) } + preampDb
        frequency.toFloat() to gain.toFloat()
    }
}

/**
 * 自动余量：按均衡和增强效果的最大提升量预先降低前级，
 * 剩余的瞬时峰值交给限幅器，避免破音又不会让整体音量掉太多
 */
fun AudioEffectsSound.estimatedHeadroomDb(): Float {
    val filters = responseFilters()
    var maxBoost = 0.0
    val low = ln(20.0)
    val high = ln(20_000.0)
    val points = 160
    for (index in 0 until points) {
        val frequency = exp(low + (high - low) * index / (points - 1))
        maxBoost = max(maxBoost, filters.sumOf { it.magnitudeDb(frequency, RESPONSE_SAMPLE_RATE) })
    }
    val enhancement = 2.5 * virtualBass + 1.5 * clarity + 1.0 * warmth + 1.5 * vocal
    return (0.85 * maxBoost + enhancement).toFloat().coerceIn(0f, 18f)
}
