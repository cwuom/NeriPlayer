package moe.ouom.neriplayer.ui.onboarding

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign

private const val STARTUP_DEVICE_SCAN_MILLIS = 1_500

@Composable
internal fun startupMotionPresetLabel(preset: StartupMotionPreset?): String = stringResource(
    when (preset) {
        StartupMotionPreset.Immersive -> AppR.string.onboarding_motion_preset_immersive
        StartupMotionPreset.Balanced -> AppR.string.onboarding_motion_preset_balanced
        StartupMotionPreset.Light -> AppR.string.onboarding_motion_preset_light
        null -> AppR.string.onboarding_motion_custom
    }
)

internal fun startupMotionPresetIcon(preset: StartupMotionPreset?): ImageVector = when (preset) {
    StartupMotionPreset.Immersive -> Icons.Rounded.Diamond
    StartupMotionPreset.Balanced -> Icons.Rounded.AutoAwesome
    StartupMotionPreset.Light -> Icons.Rounded.Bolt
    null -> Icons.Rounded.Speed
}

@Composable
internal fun StartupMotionStepBody(
    spec: StartupDeviceSpec,
    recommendation: StartupMotionRecommendation,
    selectedPreset: StartupMotionPreset?,
    config: StartupMotionConfig,
    uiStyle: UiStyle,
    scanPlayed: Boolean,
    onScanPlayed: () -> Unit,
    onSelectPreset: (StartupMotionPreset) -> Unit,
    onConfigChange: (StartupMotionConfig) -> Unit
) {
    val scan = remember { Animatable(if (scanPlayed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (scan.value < 1f) {
            scan.animateTo(1f, tween(STARTUP_DEVICE_SCAN_MILLIS, easing = LinearEasing))
            onScanPlayed()
        }
    }
    val scanDone = scan.value >= 1f
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StartupDeviceScanCard(spec = spec, scanProgress = { scan.value }, scanDone = scanDone)
        AnimatedVisibility(
            visible = scanDone,
            enter = fadeIn(tween(260)) + slideInVertically(spring(0.8f, 320f)) { it / 3 } +
                expandVertically(spring(0.9f, 380f))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StartupRecommendationBanner(recommendation)
                StartupPresetSelector(
                    recommended = recommendation.preset,
                    selected = selectedPreset,
                    onSelect = onSelectPreset
                )
                StartupMotionFineTune(
                    spec = spec,
                    config = config,
                    uiStyle = uiStyle,
                    onConfigChange = onConfigChange
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(AppR.string.onboarding_motion_local_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartupDeviceScanCard(
    spec: StartupDeviceSpec,
    scanProgress: () -> Float,
    scanDone: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val chips = buildList {
        spec.socLabel?.let { add(it) }
        if (spec.ramGb > 0) add(stringResource(AppR.string.onboarding_motion_spec_ram, spec.ramGb))
        add(stringResource(AppR.string.onboarding_motion_spec_refresh, spec.maxRefreshRate))
        add(stringResource(AppR.string.onboarding_motion_spec_android, Build.VERSION.RELEASE ?: spec.sdkInt.toString()))
        add(pluralStringResource(AppR.plurals.onboarding_motion_spec_cores, spec.cpuCores, spec.cpuCores))
    }
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 读数会逐条展开, 不用固有高度约束这一行, 否则后亮起的读数会被截掉
            StartupPhoneSilhouette(
                scanProgress = scanProgress,
                scanDone = scanDone,
                modifier = Modifier
                    .width(54.dp)
                    .height(104.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (scanDone) AppR.string.onboarding_motion_detected else AppR.string.onboarding_motion_detecting
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = spec.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (clean) FontWeight.Bold else FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (spec.modelCode.isNotBlank() && !spec.displayName.contains(spec.modelCode, ignoreCase = true)) {
                    Text(
                        text = spec.modelCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chips.forEachIndexed { index, chip ->
                        // 识别过程中按顺序亮起, 读数随扫描线逐条出现
                        val threshold = (index + 1f) / (chips.size + 1f)
                        AnimatedVisibility(
                            visible = scanDone || scanProgress() >= threshold,
                            enter = fadeIn(tween(200)) + scaleIn(spring(0.6f, 480f), initialScale = 0.7f)
                        ) {
                            StartupSpecChip(chip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupSpecChip(text: String) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    Surface(
        shape = if (clean) CircleShape else RoundedCornerShape(8.dp),
        color = if (clean) colors.surfaceContainerHighest.copy(alpha = 0.7f) else colors.secondaryContainer,
        contentColor = if (clean) colors.onSurface else colors.onSecondaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun StartupPhoneSilhouette(
    scanProgress: () -> Float,
    scanDone: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val doneAlpha by animateFloatAsState(
        targetValue = if (scanDone) 1f else 0f,
        animationSpec = tween(320),
        label = "startup_phone_done"
    )
    Box(
        modifier = modifier.clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .drawBehind {
                    val corner = CornerRadius(12.dp.toPx())
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(colors.primary.copy(alpha = 0.16f), colors.tertiary.copy(alpha = 0.1f))
                        ),
                        cornerRadius = corner
                    )
                    drawRoundRect(
                        color = colors.onSurface.copy(alpha = 0.55f),
                        cornerRadius = corner,
                        style = Stroke(width = stroke)
                    )
                    drawRoundRect(
                        color = colors.onSurface.copy(alpha = 0.55f),
                        topLeft = Offset(size.width * 0.38f, 5.dp.toPx()),
                        size = Size(size.width * 0.24f, 3.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                    val progress = scanProgress()
                    if (progress in 0.001f..0.999f) {
                        // 扫描线带拖尾光晕自上而下扫过机身
                        val y = size.height * progress
                        val glow = 18.dp.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to colors.primary.copy(alpha = 0.32f),
                                startY = y - glow,
                                endY = y
                            ),
                            topLeft = Offset(stroke, (y - glow).coerceAtLeast(0f)),
                            size = Size(size.width - stroke * 2, glow.coerceAtMost(y))
                        )
                        drawLine(
                            color = colors.primary,
                            start = Offset(stroke, y),
                            end = Offset(size.width - stroke, y),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(colors.primary.copy(alpha = doneAlpha), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (scanDone) {
                Icon(
                    Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun StartupRecommendationBanner(recommendation: StartupMotionRecommendation) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val reason = stringResource(
        when (recommendation.reason) {
            StartupMotionReason.FlagshipHardware -> AppR.string.onboarding_motion_reason_flagship
            StartupMotionReason.MainstreamHardware -> AppR.string.onboarding_motion_reason_mainstream
            StartupMotionReason.DimensityBlurPolicy -> AppR.string.onboarding_motion_reason_dimensity
            StartupMotionReason.EntryHardware -> AppR.string.onboarding_motion_reason_entry
            StartupMotionReason.PowerSaveMode -> AppR.string.onboarding_motion_reason_power_save
        }
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = if (clean) ContinuousRoundedCornerShape(20.dp) else RoundedCornerShape(24.dp),
        color = if (clean) colors.primary.copy(alpha = 0.1f) else colors.primaryContainer,
        contentColor = if (clean) colors.onSurface else colors.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                startupMotionPresetIcon(recommendation.preset),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        AppR.string.onboarding_motion_recommend_title,
                        startupMotionPresetLabel(recommendation.preset)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(text = reason, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StartupPresetSelector(
    recommended: StartupMotionPreset,
    selected: StartupMotionPreset?,
    onSelect: (StartupMotionPreset) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StartupMotionPreset.entries.forEach { preset ->
            StartupPresetCard(
                preset = preset,
                selected = preset == selected,
                recommended = preset == recommended,
                onClick = { onSelect(preset) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun StartupPresetCard(
    preset: StartupMotionPreset,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val corner by animateDpAsState(
        targetValue = if (selected) 26.dp else 16.dp,
        animationSpec = spring(0.7f, 420f),
        label = "startup_preset_corner"
    )
    val shape = if (clean) ContinuousRoundedCornerShape(20.dp) else RoundedCornerShape(corner)
    val container by animateColorAsState(
        targetValue = when {
            selected && clean -> colors.primary.copy(alpha = 0.12f)
            selected -> colors.secondaryContainer
            clean -> colors.surfaceContainerHigh
            else -> colors.surfaceContainerLow
        },
        animationSpec = tween(240),
        label = "startup_preset_container"
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant.copy(alpha = if (clean) 1f else 0f),
        animationSpec = tween(220),
        label = "startup_preset_border"
    )
    Surface(
        modifier = modifier
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = container,
        border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    startupMotionPresetIcon(preset),
                    contentDescription = null,
                    tint = if (selected) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.weight(1f))
                if (recommended) {
                    Text(
                        text = stringResource(AppR.string.onboarding_motion_recommended_badge),
                        modifier = Modifier
                            .background(colors.primary, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimary,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = startupMotionPresetLabel(preset),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 1
            )
            Text(
                text = stringResource(
                    when (preset) {
                        StartupMotionPreset.Immersive -> AppR.string.onboarding_motion_preset_immersive_desc
                        StartupMotionPreset.Balanced -> AppR.string.onboarding_motion_preset_balanced_desc
                        StartupMotionPreset.Light -> AppR.string.onboarding_motion_preset_light_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

private data class StartupMotionToggle(
    val titleRes: Int,
    val checked: Boolean,
    val enabled: Boolean,
    val hintRes: Int?,
    val update: (Boolean) -> StartupMotionConfig
)

@Composable
private fun StartupMotionFineTune(
    spec: StartupDeviceSpec,
    config: StartupMotionConfig,
    uiStyle: UiStyle,
    onConfigChange: (StartupMotionConfig) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(0.75f, 400f),
        label = "startup_fine_tune_chevron"
    )
    val capabilities = startupMotionCapabilities(spec)
    val clean = uiStyle == UiStyle.Clean
    val toggles = buildList {
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.settings_advanced_blur,
                checked = config.advancedBlur && capabilities.advancedBlurSupported,
                enabled = capabilities.advancedBlurSupported,
                hintRes = AppR.string.onboarding_motion_requires_android13.takeIf { !capabilities.advancedBlurSupported }
            ) { enabled ->
                if (enabled) config.copy(advancedBlur = true)
                else config.copy(advancedBlur = false, liquidGlass = false).withEnhancedAdvancedBlur(false)
            }
        )
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.settings_enhanced_advanced_blur,
                checked = capabilities.advancedBlurSupported && config.advancedBlur && config.enhancedAdvancedBlur,
                enabled = capabilities.advancedBlurSupported && config.advancedBlur,
                hintRes = when {
                    !capabilities.advancedBlurSupported -> AppR.string.onboarding_motion_requires_android13
                    !config.advancedBlur -> AppR.string.onboarding_motion_requires_blur
                    else -> null
                }
            ) { enabled -> config.withEnhancedAdvancedBlur(enabled) }
        )
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.settings_liquid_glass,
                checked = config.liquidGlass && clean && config.advancedBlur,
                enabled = clean && config.advancedBlur && capabilities.advancedBlurSupported,
                hintRes = when {
                    !clean -> CoreCommonR.string.settings_liquid_glass_requires_clean
                    !config.advancedBlur -> AppR.string.onboarding_motion_requires_blur
                    else -> null
                }
            ) { enabled -> config.copy(liquidGlass = enabled) }
        )
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.settings_enhanced_visual_effects,
                checked = config.enhancedVisualEffects && clean,
                enabled = clean,
                hintRes = CoreCommonR.string.settings_liquid_glass_requires_clean.takeIf { !clean }
            ) { enabled -> config.copy(enhancedVisualEffects = enabled) }
        )
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.settings_nowplaying_dynamic_background,
                checked = config.nowPlayingDynamicBackground,
                enabled = true,
                hintRes = null
            ) { enabled ->
                config.copy(
                    nowPlayingDynamicBackground = enabled,
                    nowPlayingAudioReactive = config.nowPlayingAudioReactive && enabled
                )
            }
        )
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.settings_nowplaying_audio_reactive,
                checked = config.nowPlayingAudioReactive && config.nowPlayingDynamicBackground,
                enabled = config.nowPlayingDynamicBackground,
                hintRes = AppR.string.onboarding_motion_requires_dynamic_background
                    .takeIf { !config.nowPlayingDynamicBackground }
            ) { enabled -> config.copy(nowPlayingAudioReactive = enabled) }
        )
        add(
            StartupMotionToggle(
                titleRes = CoreCommonR.string.lyrics_blur_effect,
                checked = config.lyricBlur,
                enabled = true,
                hintRes = null
            ) { enabled -> config.copy(lyricBlur = enabled) }
        )
        if (capabilities.highRefreshAvailable) {
            add(
                StartupMotionToggle(
                    titleRes = CoreCommonR.string.settings_prefer_high_refresh_rate,
                    checked = config.preferHighRefreshRate,
                    enabled = true,
                    hintRes = null
                ) { enabled -> config.copy(preferHighRefreshRate = enabled) }
            )
        }
    }
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(AppR.string.onboarding_motion_fine_tune),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = stringResource(AppR.string.onboarding_motion_fine_tune_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.rotate(chevron)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(0.9f, 360f)) + fadeIn(tween(220, delayMillis = 60)),
                exit = shrinkVertically(tween(240)) + fadeOut(tween(120))
            ) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    toggles.forEach { toggle ->
                        StartupMotionToggleRow(toggle = toggle, onConfigChange = onConfigChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupMotionToggleRow(
    toggle: StartupMotionToggle,
    onConfigChange: (StartupMotionConfig) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val contentAlpha = if (toggle.enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = toggle.checked,
                enabled = toggle.enabled,
                role = Role.Switch,
                onValueChange = { checked -> onConfigChange(toggle.update(checked)) }
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(toggle.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface.copy(alpha = contentAlpha)
            )
            toggle.hintRes?.let { hint ->
                Text(
                    text = stringResource(hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = toggle.checked, onCheckedChange = null, enabled = toggle.enabled)
    }
}
