package moe.ouom.neriplayer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import android.graphics.Color as AndroidColor
import moe.ouom.neriplayer.R

@Composable
fun HsvPicker(
    onColorChanged: (String) -> Unit, // 回调返回 6 位 HEX（不含#）
    initialHex: String = "0061A4"
) {
    // 初始把 HEX 转为 HSV
    fun hexToHsv(hex: String): FloatArray {
        val c = try { Color(("#$hex").toColorInt()) } catch (_: Throwable) { Color(0xFF0061A4) }
        val argb = c.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = (argb) and 0xFF
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        return hsv
    }

    var hsv by remember(initialHex) { mutableStateOf(hexToHsv(initialHex)) }
    val previewColor = remember(hsv) {
        val argb = AndroidColor.HSVToColor(hsv)
        Color(argb)
    }
    val hex = remember(previewColor) {
        // 转 6 位 HEX（不含 #）
        val a = previewColor.toArgb()
        val r = (a shr 16) and 0xFF
        val g = (a shr 8) and 0xFF
        val b = (a) and 0xFF
        String.format("%02X%02X%02X", r, g, b)
    }

    LaunchedEffect(hex) { onColorChanged(hex) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 预览
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(previewColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
            )
            Spacer(Modifier.size(12.dp))
            Text("#$hex", fontFamily = FontFamily.Monospace)
        }

        // Hue 0..360
        Text(stringResource(R.string.color_hue))
        Slider(
            value = hsv[0],
            onValueChange = { hsv = floatArrayOf(it, hsv[1], hsv[2]) },
            valueRange = 0f..360f
        )

        // Saturation 0..1
        Text(stringResource(R.string.color_saturation))
        Slider(
            value = hsv[1],
            onValueChange = { hsv = floatArrayOf(hsv[0], it, hsv[2]) },
            valueRange = 0f..1f
        )

        // Value 0..1
        Text(stringResource(R.string.color_value))
        Slider(
            value = hsv[2],
            onValueChange = { hsv = floatArrayOf(hsv[0], hsv[1], it) },
            valueRange = 0f..1f
        )
    }
}