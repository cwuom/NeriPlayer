package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetIds
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.SpeakerOptimizerSettings
import moe.ouom.neriplayer.data.model.playback.effects.updateProfileFor
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro

/** 所有音效修改都按当前输出设备写入对应配置，并由播放器统一防抖保存 */
internal class AudioEffectsEditor(private val route: AudioOutputRoute) {
    fun settings(transform: (AudioEffectsSettings) -> AudioEffectsSettings) {
        PlayerManager.updateAudioEffects(transform = transform)
    }

    fun profile(transform: (AudioEffectsProfile) -> AudioEffectsProfile) {
        settings { it.updateProfileFor(route, transform) }
    }

    /** 手动调任意音色参数都会自动开启音效并标记为自定义 */
    fun sound(transform: (AudioEffectsSound) -> AudioEffectsSound) {
        profile { it.copy(enabled = true, presetId = AudioEffectsPresetIds.CUSTOM, sound = transform(it.sound)) }
    }

    fun speaker(transform: (SpeakerOptimizerSettings) -> SpeakerOptimizerSettings) {
        settings { it.copy(speaker = transform(it.speaker)) }
    }
}

@Composable
internal fun AudioEffectsCardIntro(title: String, description: String) {
    MiuixSettingsSectionIntro(title = title, description = description)
}

@Composable
internal fun AudioEffectsSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ListItem(
        modifier = modifier.settingsItemClickable(enabled = enabled) { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = {
            MiuixSettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * 拖动时实时生效；值文字随手指更新，松手后由外部状态回填，
 * 避免设置流回写时滑块跳动
 */
@Composable
internal fun AudioEffectsSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    formatValue: (Float) -> String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    step: Float = 0f,
    enabled: Boolean = true
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatValue(shown),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MiuixSettingsSlider(
            value = shown.coerceIn(valueRange),
            onValueChange = { raw ->
                val next = if (step > 0f) snapToStep(raw, step) else raw
                dragging = next
                onValueChange(next)
            },
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = { dragging = Float.NaN }
        )
    }
}

@Composable
internal fun AudioEffectsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
