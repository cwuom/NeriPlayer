package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_LEFT
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_TRANSLATION_STYLE_SCALE
import moe.ouom.neriplayer.data.settings.MIN_FLOATING_LYRICS_MAX_WIDTH_DP
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsColorHex

private val FloatingLyricsPreviewShape = RoundedCornerShape(22.dp)
private val FloatingLyricsStageShape = RoundedCornerShape(18.dp)
private val FloatingLyricsWidthShape = RoundedCornerShape(14.dp)

@Composable
internal fun FloatingLyricsPreview(preferences: FloatingLyricsPreferences) {
    val textColor = Color(
        ("#${normalizeFloatingLyricsColorHex(preferences.textColorHex)}").toColorInt()
    )
    val outlineColor = Color(
        ("#${normalizeFloatingLyricsColorHex(preferences.outlineColorHex)}").toColorInt()
    )
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FloatingLyricsPreviewShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f)
                    )
                )
            )
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_floating_lyrics_preview_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(FloatingLyricsStageShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.30f))
        ) {
            val minWidth = if (maxWidth < MIN_FLOATING_LYRICS_MAX_WIDTH_DP.dp) {
                maxWidth
            } else {
                MIN_FLOATING_LYRICS_MAX_WIDTH_DP.dp
            }
            val preferredWidth = preferences.maxWidthDp.dp
            val targetWidth = when {
                preferredWidth < minWidth -> minWidth
                preferredWidth > maxWidth -> maxWidth
                else -> preferredWidth
            }
            val animatedWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "floating_lyrics_preview_width"
            )
            val horizontalTravel = (maxWidth - animatedWidth).coerceAtLeast(0.dp)
            val translationFontSizeSp = (
                preferences.fontSizeSp * FLOATING_LYRICS_TRANSLATION_STYLE_SCALE
            ).coerceAtLeast(6f)
            val lineBlockHeight = with(density) {
                (preferences.fontSizeSp + 4f).sp.toDp() +
                    (translationFontSizeSp + 4f).sp.toDp()
            } + 2.dp
            val verticalTravel = (maxHeight - lineBlockHeight - 12.dp).coerceAtLeast(0.dp)
            val offsetX by animateDpAsState(
                targetValue = horizontalTravel * preferences.positionX,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "floating_lyrics_preview_x"
            )
            val offsetY by animateDpAsState(
                targetValue = verticalTravel * preferences.positionY,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "floating_lyrics_preview_y"
            )
            Box(
                modifier = Modifier
                    .offset {
                        with(density) {
                            IntOffset(offsetX.roundToPx(), offsetY.roundToPx())
                        }
                    }
                    .width(animatedWidth)
                    .border(
                        width = 1.dp,
                        color = outlineColor.copy(alpha = 0.32f),
                        shape = FloatingLyricsWidthShape
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = lineBlockHeight),
                    verticalArrangement = if (preferences.showTranslation) {
                        Arrangement.spacedBy(2.dp)
                    } else {
                        Arrangement.Center
                    }
                ) {
                    OutlinedFloatingPreviewText(
                        text = stringResource(R.string.settings_floating_lyrics_preview_line),
                        textColor = textColor.copy(alpha = preferences.lyricAlpha),
                        outlineColor = outlineColor.copy(alpha = preferences.lyricAlpha),
                        fontSizeSp = preferences.fontSizeSp,
                        outlineWidthDp = preferences.outlineWidthDp,
                        textAlign = preferences.toTextAlign(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (preferences.showTranslation) {
                        OutlinedFloatingPreviewText(
                            text = stringResource(R.string.settings_floating_lyrics_preview_translation),
                            textColor = textColor.copy(alpha = preferences.translationAlpha),
                            outlineColor = outlineColor.copy(alpha = preferences.translationAlpha),
                            fontSizeSp = translationFontSizeSp,
                            outlineWidthDp = preferences.translationOutlineWidthDp,
                            textAlign = preferences.toTextAlign(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlinedFloatingPreviewText(
    text: String,
    textColor: Color,
    outlineColor: Color,
    fontSizeSp: Float,
    outlineWidthDp: Float,
    textAlign: TextAlign,
    modifier: Modifier = Modifier
) {
    val strokeWidth = with(LocalDensity.current) { outlineWidthDp.dp.toPx() }
    Box(modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = outlineColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 4f).sp,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodyLarge.copy(drawStyle = Stroke(width = strokeWidth))
        )
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = textColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 4f).sp,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

private fun FloatingLyricsPreferences.toTextAlign(): TextAlign {
    return when (alignment) {
        FLOATING_LYRICS_ALIGNMENT_LEFT -> TextAlign.Left
        FLOATING_LYRICS_ALIGNMENT_RIGHT -> TextAlign.Right
        else -> TextAlign.Center
    }
}
