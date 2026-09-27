package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch

private data class ThemeOption(
    val value: String,
    val labelRes: Int,
    val descriptionRes: Int
)

internal data class ThemeRevealRequest(
    val mode: ThemeMode,
    val origin: Offset,
    val radiusPx: Float
)

internal fun resolveManualThemeReveal(
    index: Int,
    currentMode: ThemeMode,
    tabsTopLeftInWindow: Offset,
    tabsWidthPx: Float,
    tabsHeightPx: Float
): ThemeRevealRequest? {
    val targetMode = if (index == 0) ThemeMode.LIGHT else ThemeMode.DARK
    if (targetMode == currentMode) return null

    val tabWidthPx = tabsWidthPx / 2f
    val origin = if (tabWidthPx > 0f && tabsHeightPx > 0f) {
        tabsTopLeftInWindow + Offset(
            x = tabWidthPx * (index + 0.5f),
            y = tabsHeightPx / 2f
        )
    } else {
        Offset.Zero
    }
    return ThemeRevealRequest(targetMode, origin, 1f)
}

internal fun resolveAutoThemeToggle(
    currentMode: ThemeMode,
    isDarkTheme: Boolean,
    switchCenterInWindow: Offset?,
    revealStartRadiusPx: Float
): ThemeRevealRequest {
    val targetMode = when {
        currentMode != ThemeMode.AUTO -> ThemeMode.AUTO
        isDarkTheme -> ThemeMode.DARK
        else -> ThemeMode.LIGHT
    }
    return ThemeRevealRequest(targetMode, switchCenterInWindow ?: Offset.Zero, revealStartRadiusPx)
}

internal class AutoThemeModeController {
    var autoEnabled: Boolean = false
        private set
    private var currentMode: ThemeMode = ThemeMode.LIGHT
    private var isDarkTheme: Boolean = false
    private var onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit = { _, _, _ -> }
    private var switchCenterInWindow: Offset? = null
    private var revealStartRadiusPx: Float = 18f

    fun update(
        themeMode: ThemeMode,
        isDarkTheme: Boolean,
        onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit
    ) {
        currentMode = themeMode
        autoEnabled = themeMode == ThemeMode.AUTO
        this.isDarkTheme = isDarkTheme
        this.onThemeModeRequest = onThemeModeRequest
    }

    fun updateGeometry(coordinates: LayoutCoordinates) {
        revealStartRadiusPx = maxOf(coordinates.size.width, coordinates.size.height) / 2f
        switchCenterInWindow = coordinates.positionInWindow() + Offset(
            x = coordinates.size.width / 2f,
            y = coordinates.size.height / 2f
        )
    }

    fun toggle() {
        val request = resolveAutoThemeToggle(
            currentMode,
            isDarkTheme,
            switchCenterInWindow,
            revealStartRadiusPx
        )
        onThemeModeRequest(request.mode, request.origin, request.radiusPx)
    }
}

@Composable
internal fun ThemeModeSelectorListItem(
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    modifier: Modifier
) {
    var tabsTopLeftInWindow by remember { mutableStateOf(Offset.Zero) }
    var tabsWidthPx by remember { mutableFloatStateOf(0f) }
    var tabsHeightPx by remember { mutableFloatStateOf(0f) }
    val selectedIndex = if (isDarkTheme) 1 else 0

    ListItem(
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Brightness4,
                contentDescription = stringResource(R.string.settings_theme_mode),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_theme_mode)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_theme_mode_desc))
                MiuixSettingsSegmentedTabs(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        tabsTopLeftInWindow = coordinates.positionInWindow()
                        tabsWidthPx = coordinates.size.width.toFloat()
                        tabsHeightPx = coordinates.size.height.toFloat()
                    },
                    labels = listOf(
                        stringResource(R.string.settings_theme_mode_light),
                        stringResource(R.string.settings_theme_mode_dark)
                    ),
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { index ->
                        resolveManualThemeReveal(
                            index,
                            themeMode,
                            tabsTopLeftInWindow,
                            tabsWidthPx,
                            tabsHeightPx
                        )?.let { request ->
                            onThemeModeRequest(request.mode, request.origin, request.radiusPx)
                        }
                    }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun ThemeAutoModeListItem(
    themeMode: ThemeMode,
    isDarkTheme: Boolean,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit
) {
    val controller = remember { AutoThemeModeController() }
    controller.update(themeMode, isDarkTheme, onThemeModeRequest)

    ThemeAutoModeRow(
        autoEnabled = controller.autoEnabled,
        onToggle = controller::toggle,
        onMeasured = controller::updateGeometry
    )
}

@Composable
private fun ThemeAutoModeRow(
    autoEnabled: Boolean,
    onToggle: () -> Unit,
    onMeasured: (LayoutCoordinates) -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.BrightnessAuto,
                contentDescription = stringResource(R.string.settings_theme_mode_auto),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_theme_mode_auto)) },
        supportingContent = {
            Text(stringResource(R.string.settings_theme_mode_auto_desc))
        },
        trailingContent = {
            Box(
                modifier = Modifier.onGloballyPositioned(onMeasured)
                    .size(width = 56.dp, height = 40.dp)
                    .settingsItemClickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                MiuixSettingsSwitch(
                    checked = autoEnabled,
                    onCheckedChange = null
                )
            }
        },
        modifier = Modifier.settingsItemClickable(onClick = onToggle),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun ThemePaletteStyleSelector(
    selectedStyle: String,
    onStyleChange: (String) -> Unit,
    modifier: Modifier
) {
    val normalizedStyle = ThemeDefaults.normalizePaletteStyle(selectedStyle)
    val options = listOf(
        ThemeOption("TonalSpot", R.string.settings_theme_style_tonal_spot, R.string.settings_theme_style_tonal_spot_desc),
        ThemeOption("Neutral", R.string.settings_theme_style_neutral, R.string.settings_theme_style_neutral_desc),
        ThemeOption("Vibrant", R.string.settings_theme_style_vibrant, R.string.settings_theme_style_vibrant_desc),
        ThemeOption("Expressive", R.string.settings_theme_style_expressive, R.string.settings_theme_style_expressive_desc),
        ThemeOption("Monochrome", R.string.settings_theme_style_monochrome, R.string.settings_theme_style_monochrome_desc),
        ThemeOption("Fidelity", R.string.settings_theme_style_fidelity, R.string.settings_theme_style_fidelity_desc)
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_theme_palette_style),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.settings_theme_palette_style_desc),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        options.forEach { option ->
            MiuixSettingsChoiceRow(
                title = stringResource(option.labelRes),
                subtitle = stringResource(option.descriptionRes),
                selected = normalizedStyle == option.value,
                onClick = { onStyleChange(option.value) }
            )
        }
    }
}

@Composable
internal fun ThemeColorSpecSelector(
    selectedSpec: String,
    onSpecChange: (String) -> Unit,
    modifier: Modifier
) {
    val normalizedSpec = ThemeDefaults.normalizeColorSpec(selectedSpec)
    val options = listOf(
        ThemeDefaults.COLOR_SPECS[0] to stringResource(R.string.settings_theme_color_spec_2021),
        ThemeDefaults.COLOR_SPECS[1] to stringResource(R.string.settings_theme_color_spec_2025)
    )

    ListItem(
        modifier = modifier,
        headlineContent = { Text(stringResource(R.string.settings_theme_color_spec)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_theme_color_spec_desc))
                MiuixSettingsSegmentedTabs(
                    labels = options.map { it.second },
                    selectedIndex = options.indexOfFirst { it.first == normalizedSpec }.coerceAtLeast(0),
                    onSelectedIndexChange = { index -> onSpecChange(options[index].first) }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
