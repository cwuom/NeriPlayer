package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.data.settings.appearance.UiDensityScalePreference
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton

@Composable
internal fun StartupPersonalizeStepBody(
    pendingUiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onUiScaleCommit: () -> Unit,
    backgroundImageUri: String?,
    backgroundImageBlur: Float,
    backgroundImageAlpha: Float,
    onSelectBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundBlurChange: (Float) -> Unit,
    onBackgroundBlurCommit: (Float) -> Unit,
    onBackgroundAlphaChange: (Float) -> Unit,
    onBackgroundAlphaCommit: (Float) -> Unit,
    isDarkTheme: Boolean,
    onThemeToggleRequest: (Offset, Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val uiScaleRange = UiDensityScalePreference.forDevice(configuration.smallestScreenWidthDp, configuration.uiMode)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HintCard(
            title = stringResource(AppR.string.onboarding_theme_mode_title),
            body = if (isDarkTheme) {
                stringResource(CoreCommonR.string.settings_theme_toggle_light)
            } else {
                stringResource(CoreCommonR.string.settings_theme_toggle_dark)
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                ThemeModeActionButton(
                    isDarkTheme = isDarkTheme,
                    onToggleRequest = onThemeToggleRequest
                )
            }
        }
        HintCard(
            title = stringResource(CoreCommonR.string.settings_ui_scale),
            body = stringResource(AppR.string.onboarding_ui_scale_hint, (pendingUiScale * 100).roundToInt())
        ) {
            Slider(
                value = pendingUiScale,
                onValueChange = onUiScaleChange,
                onValueChangeFinished = onUiScaleCommit,
                valueRange = uiScaleRange.valueRange,
                steps = uiScaleRange.steps
            )
        }
        HintCard(
            title = stringResource(CoreCommonR.string.background_custom),
            body = stringResource(AppR.string.onboarding_background_hint)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HapticOutlinedButton(
                    onClick = onSelectBackground,
                    modifier = Modifier.weight(1f),
                    shape = OnboardingControlShape,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (backgroundImageUri == null) {
                            stringResource(AppR.string.onboarding_background_select)
                        } else {
                            stringResource(AppR.string.onboarding_background_change)
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (backgroundImageUri != null) {
                    HapticOutlinedButton(
                        onClick = onClearBackground,
                        modifier = Modifier.weight(1f),
                        shape = OnboardingControlShape,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(AppR.string.onboarding_background_clear),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (backgroundImageUri != null) {
                Text(
                    stringResource(CoreCommonR.string.background_blur),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = backgroundImageBlur,
                    onValueChange = onBackgroundBlurChange,
                    onValueChangeFinished = { onBackgroundBlurCommit(backgroundImageBlur) },
                    valueRange = 0f..25f
                )
                Text(
                    stringResource(CoreCommonR.string.background_opacity),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = backgroundImageAlpha,
                    onValueChange = onBackgroundAlphaChange,
                    onValueChangeFinished = { onBackgroundAlphaCommit(backgroundImageAlpha) },
                    valueRange = 0.1f..1.0f
                )
            }
        }
        Text(
            text = stringResource(AppR.string.onboarding_complete_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }
}
