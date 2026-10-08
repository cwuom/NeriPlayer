package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
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

    fun resetSection(section: AudioEffectsSection) {
        when (section) {
            AudioEffectsSection.SPEED -> {
                settings { it.withSpeedSectionDefaults() }
                PlayerManager.resetPlaybackSpeedAndPitch()
            }
            AudioEffectsSection.SPEAKER -> speaker { it.resetTuning() }
            else -> profile { it.resetSection(section) }
        }
    }

    fun setSectionEnabled(
        section: AudioEffectsSection,
        enabled: Boolean,
        current: AudioEffectsSettings,
        soundState: PlaybackSoundState
    ) {
        section.soundSection?.let { soundSection ->
            profile { it.withSectionEnabled(soundSection, enabled) }
            return
        }
        when (section) {
            AudioEffectsSection.SPEAKER -> speaker { it.copy(enabled = enabled) }
            AudioEffectsSection.SPEED -> {
                val change = current.toggleSpeedSection(enabled, soundState)
                settings { it.toggleSpeedSection(enabled, soundState).settings }
                PlayerManager.setPlaybackSpeedAndPitch(change.speed, change.pitch)
            }
            else -> Unit
        }
    }
}

/** 分区被关掉时，里面的调节全部变灰，避免误以为还在生效 */
internal val LocalAudioEffectsSectionEnabled = compositionLocalOf { true }

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
    val active = enabled && LocalAudioEffectsSectionEnabled.current
    ListItem(
        modifier = modifier.settingsItemClickable(enabled = active) { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = {
            MiuixSettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = active)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * 默认拖动时实时生效；commitOnRelease 时只在松手后提交一次，
 * 用于倍速与音调这类每次变化都会让音频管线重建的参数
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
    enabled: Boolean = true,
    input: AudioEffectsValueInput? = null,
    commitOnRelease: Boolean = false
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    var editing by remember { mutableStateOf(false) }
    val shown = if (dragging.isNaN()) value else dragging
    val active = enabled && LocalAudioEffectsSectionEnabled.current
    if (commitOnRelease) {
        LaunchedEffect(value) { dragging = Float.NaN }
    }
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
            AudioEffectsValueChip(
                text = formatValue(shown),
                editable = input != null && active,
                onClick = { editing = true }
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
                if (!commitOnRelease) onValueChange(next)
            },
            valueRange = valueRange,
            enabled = active,
            onValueChangeFinished = {
                val released = dragging
                if (commitOnRelease) {
                    if (!released.isNaN()) onValueChange(released)
                } else {
                    dragging = Float.NaN
                }
            }
        )
    }
    if (editing && input != null) {
        AudioEffectsValueInputDialog(
            title = title,
            value = shown,
            valueRange = valueRange,
            input = input,
            onDismiss = { editing = false },
            onConfirm = { typed ->
                editing = false
                onValueChange(typed)
            }
        )
    }
}

/** 可点击的数值标签，点按后弹出精确输入 */
@Composable
internal fun AudioEffectsValueChip(text: String, editable: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val label = stringResource(CoreCommonR.string.audio_effects_input_edit)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        color = primary,
        modifier = if (editable) {
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(primary.copy(alpha = 0.1f))
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        } else {
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        }
    )
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
