package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
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
import moe.ouom.neriplayer.data.model.playback.effects.effective
import moe.ouom.neriplayer.data.model.playback.effects.profileFor
import moe.ouom.neriplayer.data.model.playback.effects.saveUserPreset
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun AudioEffectsSettingsPage(usbExclusive: Boolean, modifier: Modifier = Modifier) {
    val settings by PlayerManager.audioEffectsSettingsFlow.collectAsStateWithLifecycle()
    val route by PlayerManager.audioOutputRouteFlow.collectAsStateWithLifecycle()
    val stats by PlayerManager.audioEffectsStatsFlow.collectAsStateWithLifecycle()
    val soundState by PlayerManager.playbackSoundStateFlow.collectAsStateWithLifecycle()
    val editor = remember(route) { AudioEffectsEditor(route) }
    val profile = settings.profileFor(route)
    var section by rememberSaveable { mutableStateOf(AudioEffectsSection.PRESETS) }
    var confirmReset by remember { mutableStateOf<AudioEffectsSection?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MiuixSettingsSectionCard { AudioEffectsOverview(settings, profile, route, stats, editor) }
        AudioEffectsSectionTabs(
            selected = section,
            isModified = { it.isModified(profile, settings, soundState) },
            onSelect = { section = it }
        )
        val sectionEnabled = section.isEnabled(profile, settings)
        if (section.switchable) {
            MiuixSettingsSectionCard {
                AudioEffectsSectionSwitch(
                    section = section,
                    checked = sectionEnabled,
                    route = route,
                    onCheckedChange = { enabled -> editor.setSectionEnabled(section, enabled, settings, soundState) }
                )
            }
        }
        CompositionLocalProvider(LocalAudioEffectsSectionEnabled provides sectionEnabled) {
            AudioEffectsSectionContent(section, settings, profile, route, soundState, usbExclusive, editor)
        }
        if (section.resettable) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                AudioEffectsGlassButton(
                    text = stringResource(CoreCommonR.string.audio_effects_section_reset),
                    onClick = { confirmReset = section }
                )
            }
        }
    }
    confirmReset?.let { target ->
        val name = stringResource(target.labelRes)
        MiuixSettingsDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text(stringResource(CoreCommonR.string.audio_effects_section_reset)) },
            text = { Text(stringResource(CoreCommonR.string.audio_effects_section_reset_confirm, name)) },
            confirmButton = {
                MiuixSettingsTextButton(onClick = {
                    editor.resetSection(target)
                    confirmReset = null
                }) {
                    Text(stringResource(CoreCommonR.string.action_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { confirmReset = null }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun AudioEffectsSectionContent(
    section: AudioEffectsSection,
    settings: AudioEffectsSettings,
    profile: AudioEffectsProfile,
    route: AudioOutputRoute,
    soundState: PlaybackSoundState,
    usbExclusive: Boolean,
    editor: AudioEffectsEditor
) {
    val sound = profile.sound
    when (section) {
        AudioEffectsSection.PRESETS -> MiuixSettingsSectionCard { AudioEffectsPresetCard(settings, profile, editor) }
        AudioEffectsSection.EQUALIZER -> {
            MiuixSettingsSectionCard { AudioEffectsEqualizerCard(sound, settings, editor) }
            MiuixSettingsSectionCard { AudioEffectsCorrectionCard(sound, editor) }
        }
        AudioEffectsSection.TONE -> {
            MiuixSettingsSectionCard { AudioEffectsToneCard(sound, editor) }
            MiuixSettingsSectionCard { AudioEffectsCharacterCard(sound, editor) }
        }
        AudioEffectsSection.SPACE -> MiuixSettingsSectionCard { AudioEffectsSpaceCard(sound, editor) }
        AudioEffectsSection.DYNAMICS -> MiuixSettingsSectionCard { AudioEffectsDynamicsCard(sound, editor) }
        AudioEffectsSection.SPEAKER -> MiuixSettingsSectionCard { AudioEffectsSpeakerCard(settings, editor) }
        AudioEffectsSection.SPEED -> MiuixSettingsSectionCard { AudioEffectsSpeedCard(soundState, settings, usbExclusive, editor) }
        AudioEffectsSection.ADVANCED -> MiuixSettingsSectionCard { AudioEffectsAdvancedCard(settings, route, editor) }
    }
}

@Composable
private fun AudioEffectsSectionSwitch(
    section: AudioEffectsSection,
    checked: Boolean,
    route: AudioOutputRoute,
    onCheckedChange: (Boolean) -> Unit
) {
    val description = when (section) {
        AudioEffectsSection.SPEED -> stringResource(CoreCommonR.string.audio_effects_section_switch_speed_desc)
        AudioEffectsSection.DYNAMICS -> stringResource(CoreCommonR.string.audio_effects_section_switch_dynamics_desc)
        AudioEffectsSection.SPEAKER -> if (checked && route != AudioOutputRoute.SPEAKER) {
            stringResource(CoreCommonR.string.audio_effects_speaker_inactive)
        } else {
            stringResource(CoreCommonR.string.audio_effects_section_switch_desc)
        }
        else -> stringResource(CoreCommonR.string.audio_effects_section_switch_desc)
    }
    AudioEffectsSwitchRow(
        title = stringResource(section.switchTitleRes ?: section.labelRes),
        description = description,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

private val SectionTabShape = RoundedCornerShape(24.dp)
private val GlassButtonShape = RoundedCornerShape(999.dp)

/** 分区标签接入高级模糊，样式与媒体库等页面的顶部标签一致 */
@Composable
private fun AudioEffectsSectionTabs(
    selected: AudioEffectsSection,
    isModified: (AudioEffectsSection) -> Boolean,
    onSelect: (AudioEffectsSection) -> Unit
) {
    val dotColor = MaterialTheme.colorScheme.primary
    AdvancedGlassSurface(
        role = AdvancedGlassRole.ScreenTopTab,
        modifier = Modifier
            .fillMaxWidth()
            .clip(SectionTabShape),
        shape = SectionTabShape,
        fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tintColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected.ordinal,
            edgePadding = 8.dp,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            AudioEffectsSection.entries.forEach { item ->
                Tab(
                    selected = item == selected,
                    onClick = { onSelect(item) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(item.labelRes))
                            if (isModified(item)) {
                                Box(
                                    Modifier
                                        .padding(start = 4.dp)
                                        .size(6.dp)
                                        .background(dotColor, CircleShape)
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

/** 胶囊按钮同样走高级模糊与进阶模糊；未开启模糊时退回半透明底色 */
@Composable
private fun AudioEffectsGlassButton(text: String, onClick: () -> Unit) {
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SettingsSection,
        modifier = Modifier.clip(GlassButtonShape),
        shape = GlassButtonShape,
        fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Outlined.RestartAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
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
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (stats.active) {
        AudioEffectsLimiterLine(stats = stats, limiterEnabled = profile.sound.effective().limiterEnabled)
    }
}

private const val LIMITER_NOTICE_HOLD_MS = 4_000L

/**
 * 防破音状态常驻一行且只占一行：压限提示每秒都可能出现或消失，
 * 如果按需插入会让整页高度来回变化、窗口上下抖动
 */
@Composable
private fun AudioEffectsLimiterLine(stats: AudioEffectsRuntimeStats, limiterEnabled: Boolean) {
    var heldReductionDb by remember { mutableFloatStateOf(0f) }
    val limiting = shouldShowLimiterNotice(stats)
    LaunchedEffect(limiting, stats.limiterReductionDb) {
        if (limiting) heldReductionDb = stats.limiterReductionDb
    }
    LaunchedEffect(limiting) {
        if (!limiting) {
            delay(LIMITER_NOTICE_HOLD_MS.milliseconds)
            heldReductionDb = 0f
        }
    }
    val text = when {
        !limiterEnabled -> stringResource(CoreCommonR.string.audio_effects_status_limiter_off)
        heldReductionDb > 0f -> stringResource(CoreCommonR.string.audio_effects_status_limiter, formatSignedDb(-heldReductionDb))
        else -> stringResource(CoreCommonR.string.audio_effects_status_limiter_idle)
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (heldReductionDb > 0f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
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
