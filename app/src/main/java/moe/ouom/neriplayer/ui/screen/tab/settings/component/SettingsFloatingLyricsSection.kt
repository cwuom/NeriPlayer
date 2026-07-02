package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_CENTER
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_LEFT
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.MAX_FLOATING_LYRICS_FONT_SIZE_SP
import moe.ouom.neriplayer.data.settings.MAX_FLOATING_LYRICS_MAX_WIDTH_DP
import moe.ouom.neriplayer.data.settings.MAX_FLOATING_LYRICS_OUTLINE_WIDTH_DP
import moe.ouom.neriplayer.data.settings.MIN_FLOATING_LYRICS_FONT_SIZE_SP
import moe.ouom.neriplayer.data.settings.MIN_FLOATING_LYRICS_MAX_WIDTH_DP
import moe.ouom.neriplayer.data.settings.MIN_FLOATING_LYRICS_OUTLINE_WIDTH_DP
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsFontSizeSp
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsMaxWidthDp
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsOutlineWidthDp
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsPosition
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import kotlin.math.roundToInt

@Composable
internal fun SettingsFloatingLyricsSection(
    preferences: FloatingLyricsPreferences,
    onPreferencesChange: (FloatingLyricsPreferences) -> Unit
) {
    val normalizedPreferences = remember(preferences) { preferences.normalized() }
    var pendingFontSizeSp by remember { mutableFloatStateOf(normalizedPreferences.fontSizeSp) }
    var pendingOutlineWidthDp by remember { mutableFloatStateOf(normalizedPreferences.outlineWidthDp) }
    var pendingMaxWidthDp by remember { mutableFloatStateOf(normalizedPreferences.maxWidthDp) }
    var pendingPositionX by remember { mutableFloatStateOf(normalizedPreferences.positionX) }
    var pendingPositionY by remember { mutableFloatStateOf(normalizedPreferences.positionY) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayPermissionGranted by remember {
        mutableStateOf(FloatingLyricsOverlayManager.hasOverlayPermission(context))
    }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayPermissionGranted = FloatingLyricsOverlayManager.hasOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(normalizedPreferences.fontSizeSp) {
        pendingFontSizeSp = normalizedPreferences.fontSizeSp
    }
    LaunchedEffect(normalizedPreferences.outlineWidthDp) {
        pendingOutlineWidthDp = normalizedPreferences.outlineWidthDp
    }
    LaunchedEffect(normalizedPreferences.maxWidthDp) {
        pendingMaxWidthDp = normalizedPreferences.maxWidthDp
    }
    LaunchedEffect(normalizedPreferences.positionX) {
        pendingPositionX = normalizedPreferences.positionX
    }
    LaunchedEffect(normalizedPreferences.positionY) {
        pendingPositionY = normalizedPreferences.positionY
    }
    fun updatePreferences(transform: (FloatingLyricsPreferences) -> FloatingLyricsPreferences) {
        onPreferencesChange(transform(normalizedPreferences).normalized())
    }
    fun buildPendingPreferences(
        fontSizeSp: Float = pendingFontSizeSp,
        outlineWidthDp: Float = pendingOutlineWidthDp,
        maxWidthDp: Float = pendingMaxWidthDp,
        positionX: Float = pendingPositionX,
        positionY: Float = pendingPositionY
    ): FloatingLyricsPreferences {
        return normalizedPreferences.copy(
            fontSizeSp = fontSizeSp,
            outlineWidthDp = outlineWidthDp,
            maxWidthDp = maxWidthDp,
            positionX = positionX,
            positionY = positionY
        ).normalized()
    }
    fun previewOverlay(preferences: FloatingLyricsPreferences) {
        FloatingLyricsOverlayManager.updatePreferences(preferences)
    }
    val previewPreferences = normalizedPreferences.copy(
        fontSizeSp = pendingFontSizeSp,
        outlineWidthDp = pendingOutlineWidthDp,
        maxWidthDp = pendingMaxWidthDp,
        positionX = pendingPositionX,
        positionY = pendingPositionY
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FloatingLyricsPreview(preferences = previewPreferences)
        FloatingLyricsSwitchListItem(
            title = stringResource(R.string.settings_floating_lyrics_enable),
            description = if (overlayPermissionGranted) {
                stringResource(R.string.settings_floating_lyrics_enable_desc)
            } else {
                stringResource(R.string.settings_floating_lyrics_permission_required)
            },
            checked = normalizedPreferences.enabled,
            onCheckedChange = { enabled ->
                if (enabled && !overlayPermissionGranted) {
                    FloatingLyricsOverlayManager.openOverlayPermissionSettings(context)
                }
                updatePreferences { it.copy(enabled = enabled) }
            }
        )
        FloatingLyricsSwitchListItem(
            title = stringResource(R.string.settings_floating_lyrics_hide_in_app),
            description = stringResource(R.string.settings_floating_lyrics_hide_in_app_desc),
            checked = normalizedPreferences.hideInApp,
            onCheckedChange = { hideInApp ->
                updatePreferences { it.copy(hideInApp = hideInApp) }
            }
        )
        FloatingLyricsColorPicker(
            titleRes = R.string.settings_floating_lyrics_text_color,
            selectedColorHex = normalizedPreferences.textColorHex,
            onColorSelected = { colorHex ->
                updatePreferences { it.copy(textColorHex = colorHex) }
            }
        )
        FloatingLyricsColorPicker(
            titleRes = R.string.settings_floating_lyrics_outline_color,
            selectedColorHex = normalizedPreferences.outlineColorHex,
            onColorSelected = { colorHex ->
                updatePreferences { it.copy(outlineColorHex = colorHex) }
            }
        )
        FloatingLyricsSliderListItem(
            title = stringResource(R.string.settings_floating_lyrics_font_size),
            valueText = stringResource(
                R.string.settings_floating_lyrics_font_size_value,
                pendingFontSizeSp.roundToInt()
            ),
            value = pendingFontSizeSp,
            valueRange = MIN_FLOATING_LYRICS_FONT_SIZE_SP..MAX_FLOATING_LYRICS_FONT_SIZE_SP,
            steps = 23,
            onValueChange = { value ->
                val nextValue = normalizeFloatingLyricsFontSizeSp(value.roundToInt().toFloat())
                pendingFontSizeSp = nextValue
                previewOverlay(buildPendingPreferences(fontSizeSp = nextValue))
            },
            onValueChangeFinished = {
                updatePreferences { it.copy(fontSizeSp = pendingFontSizeSp) }
            }
        )
        FloatingLyricsSliderListItem(
            title = stringResource(R.string.settings_floating_lyrics_outline_width),
            valueText = stringResource(
                R.string.settings_floating_lyrics_outline_width_value,
                pendingOutlineWidthDp
            ),
            value = pendingOutlineWidthDp,
            valueRange = MIN_FLOATING_LYRICS_OUTLINE_WIDTH_DP..MAX_FLOATING_LYRICS_OUTLINE_WIDTH_DP,
            steps = 0,
            onValueChange = { value ->
                val nextValue = normalizeFloatingLyricsOutlineWidthDp(value)
                pendingOutlineWidthDp = nextValue
                previewOverlay(buildPendingPreferences(outlineWidthDp = nextValue))
            },
            onValueChangeFinished = {
                updatePreferences { it.copy(outlineWidthDp = pendingOutlineWidthDp) }
            }
        )
        FloatingLyricsSliderListItem(
            title = stringResource(R.string.settings_floating_lyrics_max_width),
            valueText = stringResource(
                R.string.settings_floating_lyrics_max_width_value,
                pendingMaxWidthDp
            ),
            value = pendingMaxWidthDp,
            valueRange = MIN_FLOATING_LYRICS_MAX_WIDTH_DP..MAX_FLOATING_LYRICS_MAX_WIDTH_DP,
            steps = 0,
            onValueChange = { value ->
                val nextValue = normalizeFloatingLyricsMaxWidthDp(value)
                pendingMaxWidthDp = nextValue
                previewOverlay(buildPendingPreferences(maxWidthDp = nextValue))
            },
            onValueChangeFinished = {
                updatePreferences { it.copy(maxWidthDp = pendingMaxWidthDp) }
            }
        )
        FloatingLyricsSliderListItem(
            title = stringResource(R.string.settings_floating_lyrics_position_x),
            valueText = stringResource(
                R.string.settings_floating_lyrics_position_value,
                pendingPositionX * 100f
            ),
            value = pendingPositionX,
            valueRange = 0f..1f,
            steps = 0,
            onValueChange = { value ->
                val nextValue = normalizeFloatingLyricsPosition(value)
                pendingPositionX = nextValue
                previewOverlay(buildPendingPreferences(positionX = nextValue))
            },
            onValueChangeFinished = {
                updatePreferences { it.copy(positionX = pendingPositionX) }
            }
        )
        FloatingLyricsSliderListItem(
            title = stringResource(R.string.settings_floating_lyrics_position_y),
            valueText = stringResource(
                R.string.settings_floating_lyrics_position_value,
                pendingPositionY * 100f
            ),
            value = pendingPositionY,
            valueRange = 0f..1f,
            steps = 0,
            onValueChange = { value ->
                val nextValue = normalizeFloatingLyricsPosition(value)
                pendingPositionY = nextValue
                previewOverlay(buildPendingPreferences(positionY = nextValue))
            },
            onValueChangeFinished = {
                updatePreferences { it.copy(positionY = pendingPositionY) }
            }
        )
        FloatingLyricsAlignmentSelector(
            alignment = normalizedPreferences.alignment,
            onAlignmentChange = { alignment ->
                updatePreferences { it.copy(alignment = alignment) }
            }
        )
        FloatingLyricsSwitchListItem(
            title = stringResource(R.string.settings_floating_lyrics_show_translation),
            description = stringResource(R.string.settings_floating_lyrics_show_translation_desc),
            checked = normalizedPreferences.showTranslation,
            onCheckedChange = { showTranslation ->
                updatePreferences { it.copy(showTranslation = showTranslation) }
            }
        )
    }
}

@Composable
private fun FloatingLyricsSwitchListItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable { onCheckedChange(!checked) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Subtitles,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            MiuixSettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun FloatingLyricsSliderListItem(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.FormatSize,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title)
                Text(
                    text = valueText,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        supportingContent = {
            MiuixSettingsSlider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun FloatingLyricsAlignmentSelector(
    alignment: String,
    onAlignmentChange: (String) -> Unit
) {
    val alignments = listOf(
        FLOATING_LYRICS_ALIGNMENT_LEFT,
        FLOATING_LYRICS_ALIGNMENT_CENTER,
        FLOATING_LYRICS_ALIGNMENT_RIGHT
    )
    val selectedIndex = alignments.indexOf(alignment).takeIf { it >= 0 } ?: 1

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.settings_floating_lyrics_alignment),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_floating_lyrics_alignment)) },
        supportingContent = {
            MiuixSettingsSegmentedTabs(
                labels = listOf(
                    stringResource(R.string.settings_floating_lyrics_align_left),
                    stringResource(R.string.settings_floating_lyrics_align_center),
                    stringResource(R.string.settings_floating_lyrics_align_right)
                ),
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index -> onAlignmentChange(alignments[index]) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(top = 8.dp)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
