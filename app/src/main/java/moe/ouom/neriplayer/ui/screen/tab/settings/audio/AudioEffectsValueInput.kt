package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** 输入框里显示和填写的是界面单位，滑块内部值通过 toDisplay/fromDisplay 换算 */
internal class AudioEffectsValueInput(
    val suffix: String,
    val decimals: Int,
    val toDisplay: (Float) -> Float = { it },
    val fromDisplay: (Float) -> Float = { it }
)

internal object AudioEffectsInputs {
    val Db = AudioEffectsValueInput("dB", 1)
    val Percent = AudioEffectsValueInput("%", 0, toDisplay = { it * 100f }, fromDisplay = { it / 100f })
    val Hz = AudioEffectsValueInput("Hz", 0)
    val Ms = AudioEffectsValueInput("ms", 1)
    val Ratio = AudioEffectsValueInput(":1", 1)
    val Multiplier = AudioEffectsValueInput("x", 2)
    val Semitones = AudioEffectsValueInput("", 1)
    val Plain = AudioEffectsValueInput("", 2)
    val LogFrequency = AudioEffectsValueInput("Hz", 0, toDisplay = ::sliderToFrequency, fromDisplay = ::frequencyToSlider)
}

internal fun AudioEffectsValueInput.displayText(value: Float): String =
    String.format(Locale.US, "%.${decimals}f", toDisplay(value))

internal fun AudioEffectsValueInput.displayBounds(range: ClosedFloatingPointRange<Float>): Pair<Float, Float> {
    val start = toDisplay(range.start)
    val end = toDisplay(range.endInclusive)
    return minOf(start, end) to maxOf(start, end)
}

/** 解析输入；不是数字返回 null，超出范围时夹到范围内 */
internal fun AudioEffectsValueInput.parse(text: String, range: ClosedFloatingPointRange<Float>): Float? {
    val cleaned = text.trim()
        .lowercase(Locale.US)
        .replace('，', '.')
        .replace(',', '.')
        .replace('−', '-')
        .removeSuffix(suffix.lowercase(Locale.US))
        .removePrefix("+")
        .trim()
    val number = cleaned.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val factor = 10f.pow(decimals)
    val rounded = (number * factor).roundToInt() / factor
    return fromDisplay(rounded).coerceIn(range)
}

internal fun toggleInputSign(text: String): String =
    if (text.startsWith("-")) text.removePrefix("-") else "-$text"

@Composable
internal fun AudioEffectsValueInputDialog(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    input: AudioEffectsValueInput,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    val initial = input.displayText(value)
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    val parsed = input.parse(field.text, valueRange)
    val (low, high) = input.displayBounds(valueRange)
    val format = { number: Float -> String.format(Locale.US, "%.${input.decimals}f", number) + input.suffix }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiuixSettingsTextField(
                        value = field,
                        onValueChange = { field = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        label = if (input.suffix.isNotEmpty()) {
                            { Text(input.suffix) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) })
                    )
                    if (low < 0f) {
                        MiuixSettingsTextButton(onClick = {
                            val toggled = toggleInputSign(field.text)
                            field = TextFieldValue(toggled, selection = TextRange(toggled.length))
                        }) {
                            Text("±")
                        }
                    }
                }
                Text(
                    text = if (field.text.isNotBlank() && parsed == null) {
                        stringResource(CoreCommonR.string.audio_effects_input_invalid)
                    } else {
                        stringResource(CoreCommonR.string.audio_effects_input_range, format(low), format(high))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (field.text.isNotBlank() && parsed == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}
