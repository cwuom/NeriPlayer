package moe.ouom.neriplayer.ui.screen.tab.settings.component.floating

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.lyrics.resolveFloatingLyricsEffectAlpha
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_ALIGNMENT_LEFT
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_RENDER_STYLE_OUTLINE
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_TRANSLATION_STYLE_SCALE
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_LONG_LINE_WRAP
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_PREVIEW_ALPHA_SCALE
import moe.ouom.neriplayer.data.model.settings.lyrics.resolveFloatingLyricsWidthDp
import moe.ouom.neriplayer.data.model.settings.lyrics.MIN_FLOATING_LYRICS_MAX_WIDTH_DP
import moe.ouom.neriplayer.data.model.settings.lyrics.normalizeFloatingLyricsColorHex
import moe.ouom.neriplayer.data.model.settings.lyrics.resolveFloatingLyricsPositionX
import moe.ouom.neriplayer.data.model.settings.lyrics.resolveFloatingLyricsPositionY
import kotlin.math.roundToInt

private val FloatingLyricsPreviewShape = RoundedCornerShape(22.dp)
private val FloatingLyricsStageShape = RoundedCornerShape(18.dp)
private val FloatingLyricsWidthShape = RoundedCornerShape(14.dp)

@Composable
internal fun FloatingLyricsPreview(
    preferences: FloatingLyricsPreferences,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val textColor = Color(
        ("#${normalizeFloatingLyricsColorHex(preferences.textColorHex)}").toColorInt()
    )
    val effectColor = Color(
        ("#${normalizeFloatingLyricsColorHex(preferences.outlineColorHex)}").toColorInt()
    )
    val wrapLines = preferences.longLineMode == FLOATING_LYRICS_LONG_LINE_WRAP
    Column(
        modifier = modifier
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
            text = stringResource(CoreCommonR.string.settings_floating_lyrics_preview_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FloatingLyricsStageShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.30f))
        ) {
            val minWidth = if (maxWidth < MIN_FLOATING_LYRICS_MAX_WIDTH_DP.dp) {
                maxWidth
            } else {
                MIN_FLOATING_LYRICS_MAX_WIDTH_DP.dp
            }
            val preferredWidth = resolveFloatingLyricsWidthDp(preferences, isLandscape).dp
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
            val translationFontSizeSp = (
                preferences.fontSizeSp * FLOATING_LYRICS_TRANSLATION_STYLE_SCALE
            ).coerceAtLeast(6f)
            val positionX by animateFloatAsState(
                targetValue = resolveFloatingLyricsPositionX(preferences, isLandscape),
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "floating_lyrics_preview_x"
            )
            val positionY by animateFloatAsState(
                targetValue = resolveFloatingLyricsPositionY(preferences, isLandscape),
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "floating_lyrics_preview_y"
            )
            FloatingLyricsPreviewStage(
                minimumHeight = if (preferences.sentenceCount > 1 || wrapLines) 260.dp else 132.dp,
                positionX = positionX,
                positionY = positionY
            ) {
                Box(
                    modifier = Modifier
                        .width(animatedWidth)
                        .border(
                            width = 1.dp,
                            color = effectColor.copy(alpha = 0.32f),
                            shape = FloatingLyricsWidthShape
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = if (preferences.showTranslation) {
                            Arrangement.spacedBy(2.dp)
                        } else {
                            Arrangement.Center
                        }
                    ) {
                        FloatingPreviewText(
                            text = stringResource(CoreCommonR.string.settings_floating_lyrics_preview_line),
                            textColor = textColor.copy(alpha = preferences.lyricAlpha),
                            effectColor = effectColor.copy(
                                alpha = resolveFloatingLyricsEffectAlpha(preferences.lyricAlpha)
                            ),
                            fontSizeSp = preferences.fontSizeSp,
                            effectWidthDp = preferences.outlineWidthDp,
                            usesOutline = preferences.renderStyle == FLOATING_LYRICS_RENDER_STYLE_OUTLINE,
                            textAlign = preferences.toTextAlign(),
                            maxLines = if (wrapLines) 3 else 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (preferences.showTranslation) {
                            FloatingPreviewText(
                                text = stringResource(CoreCommonR.string.settings_floating_lyrics_preview_translation),
                                textColor = textColor.copy(alpha = preferences.translationAlpha),
                                effectColor = effectColor.copy(
                                    alpha = resolveFloatingLyricsEffectAlpha(
                                        preferences.translationAlpha
                                    )
                                ),
                                fontSizeSp = translationFontSizeSp,
                                effectWidthDp = preferences.translationOutlineWidthDp,
                                usesOutline = preferences.renderStyle == FLOATING_LYRICS_RENDER_STYLE_OUTLINE,
                                textAlign = preferences.toTextAlign(),
                                maxLines = if (wrapLines) 3 else 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        for (index in 1 until preferences.sentenceCount) {
                            val previewAlpha = preferences.lyricAlpha * FLOATING_LYRICS_PREVIEW_ALPHA_SCALE
                            FloatingPreviewText(
                                text = stringResource(if (index == 1) {
                                    CoreCommonR.string.settings_floating_lyrics_preview_next
                                } else {
                                    CoreCommonR.string.settings_floating_lyrics_preview_second_next
                                }),
                                textColor = textColor.copy(alpha = previewAlpha),
                                effectColor = effectColor.copy(alpha = resolveFloatingLyricsEffectAlpha(previewAlpha)),
                                fontSizeSp = preferences.fontSizeSp,
                                effectWidthDp = preferences.outlineWidthDp,
                                usesOutline = preferences.renderStyle == FLOATING_LYRICS_RENDER_STYLE_OUTLINE,
                                textAlign = preferences.toTextAlign(),
                                maxLines = if (wrapLines) 3 else 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingLyricsPreviewStage(
    minimumHeight: Dp,
    positionX: Float,
    positionY: Float,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        // Measure the full block before positioning it; the settings list supplies vertical scrolling.
        val block = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val width = constraints.constrainWidth(block.width)
        val height = constraints.constrainHeight(maxOf(minimumHeight.roundToPx(), block.height))
        layout(width, height) {
            block.place(
                ((width - block.width).coerceAtLeast(0) * positionX).roundToInt(),
                ((height - block.height).coerceAtLeast(0) * positionY).roundToInt()
            )
        }
    }
}

@Composable
private fun FloatingPreviewText(
    text: String,
    textColor: Color,
    effectColor: Color,
    fontSizeSp: Float,
    effectWidthDp: Float,
    usesOutline: Boolean,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    val effectWidthPx = with(LocalDensity.current) { effectWidthDp.dp.toPx() }
    Box(modifier = modifier) {
        if (usesOutline) {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                color = effectColor,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 4f).sp,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = if (maxLines > 1) TextOverflow.Ellipsis else TextOverflow.Clip,
                style = MaterialTheme.typography.bodyLarge.copy(
                    drawStyle = Stroke(width = effectWidthPx)
                )
            )
        }
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = textColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 4f).sp,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = if (maxLines > 1) TextOverflow.Ellipsis else TextOverflow.Clip,
            style = if (usesOutline) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyLarge.copy(
                    shadow = Shadow(
                        color = effectColor,
                        offset = Offset(0f, effectWidthPx * 0.5f),
                        blurRadius = effectWidthPx
                    )
                )
            }
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
