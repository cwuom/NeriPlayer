package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified as isColorSpecified
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun AdvancedGlassSurface(
    role: AdvancedGlassRole,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    fallbackColor: Color = Color.Transparent,
    tintColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val controller = LocalAdvancedGlassController.current
    val availableBackdrops = LocalAdvancedGlassBackdrops.current
    val glassDepth = LocalAdvancedGlassDepth.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeNavigationOwners = LocalAdvancedGlassActiveNavigationOwners.current
    val sceneActive = LocalAdvancedGlassSceneActive.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isDarkTheme = isSystemInDarkTheme()
    val enhancedBlurRadiusDp = if (controller.isEnabled) {
        controller.normalizedEnhancedBlurRadiusDp
    } else {
        null
    }
    val tokens = advancedGlassTokens(role, isDarkTheme, enhancedBlurRadiusDp)
    val resolvedTintColor = if (tintColor.isColorSpecified) tintColor else advancedGlassRoleColor(role)
    val edgeBaseColor = MaterialTheme.colorScheme.onSurface
    val requiresContentBackdrop = role == AdvancedGlassRole.MiniPlayer ||
        role == AdvancedGlassRole.BottomNavigation
    val backdropsReady = availableBackdrops?.let { backdrops ->
        backdrops.background.positionInWindow.isSpecified &&
            (!requiresContentBackdrop || backdrops.content.positionInWindow.isSpecified)
    } == true
    val belongsToActiveNavigationScreen = requiresContentBackdrop ||
        activeNavigationOwners == null ||
        lifecycleOwner in activeNavigationOwners
    val glassEnabled = enabled && sceneActive && belongsToActiveNavigationScreen && backdropsReady &&
        canSampleAdvancedGlassBackdrop(controller, glassDepth, role)
    val registersBackdrop = glassEnabled
    val regionKey = remember { Any() }

    DisposableEffect(availableBackdrops, regionKey, registersBackdrop) {
        if (!registersBackdrop) {
            availableBackdrops?.regionRegistry?.remove(regionKey)
        }
        onDispose {
            availableBackdrops?.regionRegistry?.remove(regionKey)
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .onGloballyPositioned { coordinates ->
                val registry = availableBackdrops?.regionRegistry ?: return@onGloballyPositioned
                if (!registersBackdrop || !coordinates.isAttached) {
                    registry.remove(regionKey)
                    return@onGloballyPositioned
                }
                val bounds = coordinates.boundsInWindow()
                if (bounds.width <= 0f || bounds.height <= 0f) {
                    registry.remove(regionKey)
                    return@onGloballyPositioned
                }
                registry.update(
                    regionKey,
                    AdvancedGlassRegion(
                        role = role,
                        boundsInWindow = bounds,
                        cornerRadiiPx = resolveCornerRadiiPx(
                            shape = shape,
                            size = bounds.size,
                            layoutDirection = layoutDirection,
                            density = density
                        )
                    )
                )
            }
    ) {
        if (glassEnabled) {
            GlassColorLayer(
                shape = shape,
                color = resolvedTintColor.copy(
                    alpha = resolvedTintColor.alpha * tokens.tintAlpha
                )
            )
        } else if (fallbackColor != Color.Transparent) {
            GlassColorLayer(shape, fallbackColor)
        }

        CompositionLocalProvider(
            LocalAdvancedGlassDepth provides if (glassEnabled) glassDepth + 1 else glassDepth
        ) {
            content()
        }

        if (glassEnabled && tokens.edgeAlpha > 0f) {
            GlassEdgeLayer(
                role = role,
                shape = shape,
                color = edgeBaseColor.copy(alpha = tokens.edgeAlpha)
            )
        }
    }
}

private fun resolveCornerRadiiPx(
    shape: Shape,
    size: Size,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    density: androidx.compose.ui.unit.Density
): AdvancedGlassCornerRadii = when (
    val outline = shape.createOutline(size, layoutDirection, density)
) {
    is Outline.Rounded -> {
        val roundRect = outline.roundRect
        AdvancedGlassCornerRadii(
            topLeft = roundRect.topLeftCornerRadius.x,
            topRight = roundRect.topRightCornerRadius.x,
            bottomRight = roundRect.bottomRightCornerRadius.x,
            bottomLeft = roundRect.bottomLeftCornerRadius.x
        )
    }
    else -> AdvancedGlassCornerRadii.Zero
}

@Composable
private fun BoxScope.GlassColorLayer(shape: Shape, color: Color) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawWithCache {
                val path = Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
                }
                onDrawBehind { drawPath(path = path, color = color) }
            }
    )
}

@Composable
private fun BoxScope.GlassEdgeLayer(
    role: AdvancedGlassRole,
    shape: Shape,
    color: Color
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawWithCache {
                val path = Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
                }
                val stroke = Stroke(width = 1.dp.toPx())
                onDrawBehind {
                    if (role == AdvancedGlassRole.BottomNavigation) {
                        drawLine(
                            color = color,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = stroke.width
                        )
                    } else {
                        drawPath(path = path, color = color, style = stroke)
                    }
                }
            }
    )
}

@Composable
private fun advancedGlassRoleColor(role: AdvancedGlassRole): Color = when (role) {
    AdvancedGlassRole.MiniPlayer -> MaterialTheme.colorScheme.secondaryContainer
    AdvancedGlassRole.BottomNavigation,
    AdvancedGlassRole.ScreenTopTab,
    AdvancedGlassRole.SettingsGroup,
    AdvancedGlassRole.SettingsSection -> MaterialTheme.colorScheme.surfaceContainerHighest
    AdvancedGlassRole.SettingsHeader -> MaterialTheme.colorScheme.primaryContainer
    AdvancedGlassRole.SemanticCard -> MaterialTheme.colorScheme.surfaceContainerHigh
    AdvancedGlassRole.InlineControl -> Color.Transparent
}
