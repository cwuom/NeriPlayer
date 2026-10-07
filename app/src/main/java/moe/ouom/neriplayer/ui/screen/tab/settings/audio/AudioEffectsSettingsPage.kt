package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsBuiltInPresets
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetIds
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsRuntimeStats
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettingsCodec
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsUserPreset
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.applyBuiltInPreset
import moe.ouom.neriplayer.data.model.playback.effects.applyUserPreset
import moe.ouom.neriplayer.data.model.playback.effects.deleteUserPreset
import moe.ouom.neriplayer.data.model.playback.effects.profileFor
import moe.ouom.neriplayer.data.model.playback.effects.saveUserPreset
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard

@Composable
internal fun AudioEffectsSettingsPage(usbExclusive: Boolean, modifier: Modifier = Modifier) {
    val settings by PlayerManager.audioEffectsSettingsFlow.collectAsStateWithLifecycle()
    val route by PlayerManager.audioOutputRouteFlow.collectAsStateWithLifecycle()
    val stats by PlayerManager.audioEffectsStatsFlow.collectAsStateWithLifecycle()
    val soundState by PlayerManager.playbackSoundStateFlow.collectAsStateWithLifecycle()
    val editor = remember(route) { AudioEffectsEditor(route) }
    val profile = settings.profileFor(route)
    val sound = profile.sound
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MiuixSettingsSectionCard { AudioEffectsOverview(settings, profile, route, stats, editor) }
        MiuixSettingsSectionCard { AudioEffectsPresetCard(settings, profile, editor) }
        MiuixSettingsSectionCard { AudioEffectsEqualizerCard(sound, settings, editor) }
        MiuixSettingsSectionCard { AudioEffectsCorrectionCard(sound, editor) }
        MiuixSettingsSectionCard { AudioEffectsToneCard(sound, editor) }
        MiuixSettingsSectionCard { AudioEffectsCharacterCard(sound, editor) }
        MiuixSettingsSectionCard { AudioEffectsSpaceCard(sound, editor) }
        MiuixSettingsSectionCard { AudioEffectsDynamicsCard(sound, editor) }
        MiuixSettingsSectionCard { AudioEffectsSpeakerCard(settings, route, editor) }
        MiuixSettingsSectionCard { AudioEffectsSpeedCard(soundState, settings, usbExclusive, editor) }
        MiuixSettingsSectionCard { AudioEffectsAdvancedCard(settings, route, editor) }
    }
}

@Composable
private fun AudioEffectsOverview(
    settings: AudioEffectsSettings,
    profile: AudioEffectsProfile,
    route: AudioOutputRoute,
    stats: AudioEffectsRuntimeStats,
    editor: AudioEffectsEditor
) {
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_master),
        description = stringResource(CoreCommonR.string.audio_effects_master_desc),
        checked = profile.enabled,
        onCheckedChange = { enabled -> editor.profile { it.copy(enabled = enabled) } }
    )
    val routeLabel = stringResource(route.labelRes())
    AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_route_format, routeLabel))
    if (settings.perOutputEnabled) {
        AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_editing_route, routeLabel))
    }
    val statusText = when (val status = resolveAudioEffectsStatus(stats)) {
        is AudioEffectsStatus.Processing -> if (status.sampleRateKhz != null) {
            stringResource(CoreCommonR.string.audio_effects_status_active_rate, status.sampleRateKhz, status.cpuLoad)
        } else {
            stringResource(CoreCommonR.string.audio_effects_status_active, status.cpuLoad)
        }
        is AudioEffectsStatus.Bypassed -> stringResource(status.messageRes)
    }
    Text(
        text = statusText,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
    if (shouldShowLimiterNotice(stats)) {
        AudioEffectsNote(
            stringResource(CoreCommonR.string.audio_effects_status_limiter, formatSignedDb(-stats.limiterReductionDb))
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioEffectsPresetCard(
    settings: AudioEffectsSettings,
    profile: AudioEffectsProfile,
    editor: AudioEffectsEditor
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var tabIndex by remember { mutableIntStateOf(0) }
    var showSave by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AudioEffectsUserPreset?>(null) }
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_presets_title),
        description = stringResource(CoreCommonR.string.audio_effects_presets_desc)
    )
    MiuixSettingsSegmentedTabs(
        labels = AudioEffectsPresetTabs.map { stringResource(it.tabLabelRes()) },
        selectedIndex = tabIndex,
        onSelectedIndexChange = { tabIndex = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
    val category = AudioEffectsPresetTabs[tabIndex]
    if (category != null) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioEffectsBuiltInPresets.filter { it.category == category }.forEach { preset ->
                FilterChip(
                    selected = profile.enabled && profile.presetId == preset.id,
                    onClick = { editor.profile { it.applyBuiltInPreset(preset) } },
                    label = { Text(stringResource(audioEffectsPresetNameRes(preset.id) ?: CoreCommonR.string.audio_effects_preset_custom)) }
                )
            }
        }
    } else {
        UserPresetList(
            presets = settings.userPresets,
            selectedId = profile.presetId.takeIf { profile.enabled },
            onApply = { preset -> editor.profile { it.applyUserPreset(preset) } },
            onShare = { preset -> copyPresetCode(context, preset) },
            onDelete = { deleting = it }
        )
    }
    if (profile.presetId == AudioEffectsPresetIds.CUSTOM && profile.enabled) {
        AudioEffectsNote(
            stringResource(
                CoreCommonR.string.audio_effects_current_preset,
                stringResource(CoreCommonR.string.audio_effects_preset_custom)
            )
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        MiuixSettingsOutlinedButton(onClick = { showImport = true }) {
            Text(stringResource(CoreCommonR.string.audio_effects_import_preset))
        }
        MiuixSettingsOutlinedButton(onClick = { showSave = true }) {
            Text(stringResource(CoreCommonR.string.audio_effects_save_preset))
        }
    }
    if (showSave) {
        SavePresetDialog(
            onDismiss = { showSave = false },
            onSave = { name ->
                val id = newUserPresetId(System.currentTimeMillis())
                editor.settings { it.saveUserPreset(id, name, profile.sound) }
                editor.profile { it.copy(presetId = id) }
                AppFeedback.showToast(context = context, message = resources.getString(CoreCommonR.string.audio_effects_preset_saved, name.trim()))
                showSave = false
            }
        )
    }
    if (showImport) {
        ImportPresetDialog(
            onDismiss = { showImport = false },
            onImport = { code ->
                val imported = AudioEffectsSettingsCodec.decodeUserPresetOrNull(code)
                if (imported == null) {
                    AppFeedback.showToast(context = context, message = resources.getString(CoreCommonR.string.audio_effects_import_failed))
                } else {
                    val id = newUserPresetId(System.currentTimeMillis())
                    editor.settings { it.saveUserPreset(id, imported.name, imported.sound) }
                    editor.profile { it.applyUserPreset(imported.copy(id = id)) }
                    AppFeedback.showToast(context = context, message = resources.getString(CoreCommonR.string.audio_effects_preset_saved, imported.name))
                    showImport = false
                }
            }
        )
    }
    deleting?.let { preset ->
        MiuixSettingsDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(CoreCommonR.string.audio_effects_delete_preset)) },
            text = { Text(stringResource(CoreCommonR.string.audio_effects_delete_preset_confirm, preset.name)) },
            confirmButton = {
                MiuixSettingsTextButton(onClick = {
                    editor.settings { it.deleteUserPreset(preset.id) }
                    deleting = null
                }) {
                    Text(stringResource(CoreCommonR.string.action_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { deleting = null }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun UserPresetList(
    presets: List<AudioEffectsUserPreset>,
    selectedId: String?,
    onApply: (AudioEffectsUserPreset) -> Unit,
    onShare: (AudioEffectsUserPreset) -> Unit,
    onDelete: (AudioEffectsUserPreset) -> Unit
) {
    if (presets.isEmpty()) {
        AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_user_presets_empty))
        return
    }
    presets.forEach { preset ->
        val selected = preset.id == selectedId
        ListItem(
            modifier = Modifier.settingsItemClickable { onApply(preset) },
            headlineContent = {
                Text(
                    text = preset.name,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            },
            trailingContent = {
                Row {
                    IconButton(onClick = { onShare(preset) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(CoreCommonR.string.audio_effects_share_preset))
                    }
                    IconButton(onClick = { onDelete(preset) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(CoreCommonR.string.audio_effects_delete_preset))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.audio_effects_save_preset)) },
        text = {
            MiuixSettingsTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                label = { Text(stringResource(CoreCommonR.string.audio_effects_save_preset_name)) },
                placeholder = { Text(stringResource(CoreCommonR.string.audio_effects_save_preset_hint)) }
            )
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
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

@Composable
private fun ImportPresetDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.audio_effects_import_preset)) },
        text = {
            MiuixSettingsTextField(
                value = code,
                onValueChange = { code = it },
                minLines = 3,
                maxLines = 8,
                placeholder = { Text(stringResource(CoreCommonR.string.audio_effects_import_preset_hint)) }
            )
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = { onImport(code) }, enabled = code.isNotBlank()) {
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

private fun copyPresetCode(context: Context, preset: AudioEffectsUserPreset) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(preset.name, AudioEffectsSettingsCodec.encodeUserPreset(preset)))
    AppFeedback.showToast(context = context, message = context.getString(CoreCommonR.string.audio_effects_share_copied))
}
