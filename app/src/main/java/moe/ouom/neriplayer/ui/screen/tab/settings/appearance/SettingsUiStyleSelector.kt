package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriMotion
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun SettingsUiStyleSelector(modifier: Modifier = Modifier) {
    val repo = AppContainer.settingsRepo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uiStyle by repo.uiStyleFlow.collectAsStateWithLifecycle(initialValue = UiStyle.Material)
    Column(modifier = modifier.fillMaxWidth()) {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_ui_style),
            description = stringResource(CoreCommonR.string.settings_ui_style_desc)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UiStyle.entries.forEach { option ->
                UiStyleTile(
                    style = option,
                    selected = option == uiStyle,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (option != uiStyle) {
                            context.performHapticFeedback()
                            scope.launch { repo.setUiStyle(option) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun UiStyleTile(
    style: UiStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val selection by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = NeriMotion.smooth(),
        label = "ui_style_tile_selection"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = NeriMotion.smooth(),
        label = "ui_style_tile_border"
    )
    val tileShape = ContinuousRoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clip(tileShape)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .graphicsLayer {
                    val scale = 0.97f + 0.03f * selection
                    scaleX = scale
                    scaleY = scale
                }
                .border(BorderStroke((1f + 1.5f * selection).dp, borderColor), tileShape)
                .padding(5.dp)
        ) {
            when (style) {
                UiStyle.Material -> MaterialPreviewMock()
                UiStyle.Clean -> CleanPreviewMock()
            }
            if (selection > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .graphicsLayer {
                            alpha = selection
                            scaleX = 0.6f + 0.4f * selection
                            scaleY = 0.6f + 0.4f * selection
                        }
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    if (style == UiStyle.Clean) CoreCommonR.string.ui_style_clean else CoreCommonR.string.ui_style_material
                ),
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.primary else colors.onSurface
            )
            if (style == UiStyle.Clean) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(CoreCommonR.string.ui_style_preview_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            text = stringResource(
                if (style == UiStyle.Clean) {
                    CoreCommonR.string.ui_style_clean_desc
                } else {
                    CoreCommonR.string.ui_style_material_desc
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

/** Tonal Material You mock: tinted surfaces, pill nav indicator. */
@Composable
private fun MaterialPreviewMock() {
    val colors = MaterialTheme.colorScheme
    val surface = lerp(colors.surface, colors.primary, 0.08f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MockBar(widthFraction = 0.55f, color = colors.onSurface.copy(alpha = 0.7f), height = 8)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primaryContainer)
        )
        MockBar(widthFraction = 0.8f, color = colors.onSurfaceVariant.copy(alpha = 0.5f), height = 6)
        MockBar(widthFraction = 0.6f, color = colors.onSurfaceVariant.copy(alpha = 0.35f), height = 6)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceContainer)
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(width = 18.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == 0) colors.secondaryContainer else colors.onSurfaceVariant.copy(alpha = 0.25f)
                        )
                )
            }
        }
    }
}

/** Neutral clean mock: grouped white cards on a grey canvas, single accent, floating glass bar. */
@Composable
private fun CleanPreviewMock() {
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminanceApprox() > 0.5f
    val canvas = if (dark) Color(0xFF0E0E10) else Color(0xFFF4F4F7)
    val card = if (dark) Color(0xFF1C1C1F) else Color.White
    val ink = if (dark) Color(0xFFECECF0) else Color(0xFF1C1C1F)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clip(ContinuousRoundedCornerShape(16.dp))
            .background(canvas)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MockBar(widthFraction = 0.45f, color = ink, height = 9)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(ContinuousRoundedCornerShape(12.dp))
                .background(card)
                .padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(ContinuousRoundedCornerShape(5.dp))
                        .background(colors.primary)
                )
                MockBar(widthFraction = 0.7f, color = ink.copy(alpha = 0.55f), height = 5)
                MockBar(widthFraction = 0.5f, color = ink.copy(alpha = 0.3f), height = 5)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(card.copy(alpha = 0.86f))
                .border(0.5.dp, ink.copy(alpha = 0.08f), CircleShape)
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (index == 0) colors.primary else ink.copy(alpha = 0.22f))
                )
            }
        }
    }
}

@Composable
private fun MockBar(widthFraction: Float, color: Color, height: Int) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
