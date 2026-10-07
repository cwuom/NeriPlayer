package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.data.model.playback.effects.AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsGraphicBandFrequenciesHz
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.responseCurve
import kotlin.math.log2

private const val GRAPH_LEFT_DP = 30f
private const val GRAPH_BOTTOM_DP = 22f
private const val GRAPH_TOP_DP = 18f
private const val BAND_STEP_DB = 0.5f

private val BandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** 几何换算独立出来，界面与测试共用同一套坐标 */
internal class EqualizerGraphGeometry(
    private val width: Float,
    private val height: Float,
    private val left: Float,
    private val top: Float,
    private val bottom: Float
) {
    private val bandCount = AudioEffectsGraphicBandFrequenciesHz.size
    private val cell = (width - left) / bandCount
    private val plotHeight = (height - top - bottom).coerceAtLeast(1f)

    fun bandX(index: Int): Float = left + (index + 0.5f) * cell

    fun frequencyX(frequencyHz: Float): Float {
        val position = log2(frequencyHz / AudioEffectsGraphicBandFrequenciesHz.first()) + 0.5f
        return (left + position * cell).coerceIn(left, width)
    }

    fun dbY(db: Float): Float {
        val limit = AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
        val clamped = db.coerceIn(-limit, limit)
        return top + (limit - clamped) / (2f * limit) * plotHeight
    }

    fun yDb(y: Float): Float {
        val limit = AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
        val raw = limit - (y - top) / plotHeight * 2f * limit
        return snapToStep(raw.coerceIn(-limit, limit), BAND_STEP_DB)
    }

    fun nearestBand(x: Float): Int = ((x - left) / cell).toInt().coerceIn(0, bandCount - 1)

    val plotBottom: Float get() = top + plotHeight
}

@Composable
internal fun AudioEffectsEqualizerGraph(
    sound: AudioEffectsSound,
    onBandChange: (index: Int, gainDb: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val handleFill = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()
    var activeBand by remember { mutableIntStateOf(-1) }
    val latestOnBandChange by rememberUpdatedState(onBandChange)
    val curve = remember(sound) { sound.responseCurve(points = 96) }
    val bands = sound.equalizerBandsDb
    val enabled = sound.equalizerEnabled
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> activeBand = geometry(size.width.toFloat(), size.height.toFloat()).nearestBand(offset.x) },
                    onDragEnd = { activeBand = -1 },
                    onDragCancel = { activeBand = -1 },
                    onDrag = { change, _ ->
                        change.consume()
                        val band = activeBand
                        if (band >= 0) {
                            val geometry = geometry(size.width.toFloat(), size.height.toFloat())
                            latestOnBandChange(band, geometry.yDb(change.position.y))
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { offset ->
                    val geometry = geometry(size.width.toFloat(), size.height.toFloat())
                    latestOnBandChange(geometry.nearestBand(offset.x), 0f)
                })
            }
    ) {
        val geometry = geometry(size.width, size.height)
        drawGrid(geometry, gridColor, labelColor, textMeasurer)
        drawResponse(geometry, curve, primary.copy(alpha = if (enabled) 1f else 0.4f))
        bands.forEachIndexed { index, gain ->
            val center = Offset(geometry.bandX(index), geometry.dbY(gain))
            val radius = if (index == activeBand) 10.dp.toPx() else 7.dp.toPx()
            drawCircle(color = handleFill, radius = radius, center = center)
            drawCircle(color = primary, radius = radius, center = center, style = Stroke(width = 2.5.dp.toPx()))
            if (index == activeBand) {
                drawLabel(textMeasurer, formatSignedDb(gain), Offset(center.x, center.y - radius - 14.dp.toPx()), primary)
            }
        }
    }
}

private fun androidx.compose.ui.unit.Density.geometry(width: Float, height: Float) = EqualizerGraphGeometry(
    width = width,
    height = height,
    left = GRAPH_LEFT_DP.dp.toPx(),
    top = GRAPH_TOP_DP.dp.toPx(),
    bottom = GRAPH_BOTTOM_DP.dp.toPx()
)

private fun DrawScope.drawGrid(
    geometry: EqualizerGraphGeometry,
    gridColor: androidx.compose.ui.graphics.Color,
    labelColor: androidx.compose.ui.graphics.Color,
    textMeasurer: TextMeasurer
) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    listOf(12f, 6f, 0f, -6f, -12f).forEach { db ->
        val y = geometry.dbY(db)
        drawLine(
            color = gridColor.copy(alpha = if (db == 0f) 0.9f else 0.45f),
            start = Offset(GRAPH_LEFT_DP.dp.toPx(), y),
            end = Offset(size.width, y),
            strokeWidth = if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx(),
            pathEffect = if (db == 0f) null else dashed
        )
        val label = if (db > 0f) "+${db.toInt()}" else db.toInt().toString()
        drawLabel(textMeasurer, label, Offset(GRAPH_LEFT_DP.dp.toPx() / 2f, y), labelColor)
    }
    BandLabels.forEachIndexed { index, label ->
        drawLabel(textMeasurer, label, Offset(geometry.bandX(index), size.height - 8.dp.toPx()), labelColor)
    }
}

private fun DrawScope.drawResponse(
    geometry: EqualizerGraphGeometry,
    curve: List<Pair<Float, Float>>,
    color: androidx.compose.ui.graphics.Color
) {
    if (curve.isEmpty()) return
    val line = Path()
    val fill = Path()
    curve.forEachIndexed { index, (frequency, gain) ->
        val x = geometry.frequencyX(frequency)
        val y = geometry.dbY(gain)
        if (index == 0) {
            line.moveTo(x, y)
            fill.moveTo(x, geometry.dbY(0f))
            fill.lineTo(x, y)
        } else {
            line.lineTo(x, y)
            fill.lineTo(x, y)
        }
    }
    fill.lineTo(geometry.frequencyX(curve.last().first), geometry.dbY(0f))
    fill.close()
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.04f)),
            startY = 0f,
            endY = geometry.plotBottom
        )
    )
    drawPath(path = line, color = color, style = Stroke(width = 2.5.dp.toPx()))
}

private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    center: Offset,
    color: androidx.compose.ui.graphics.Color
) {
    val layout = textMeasurer.measure(text, style = TextStyle(fontSize = 10.sp, color = color))
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    )
}
