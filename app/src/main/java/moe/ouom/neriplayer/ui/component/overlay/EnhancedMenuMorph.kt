package moe.ouom.neriplayer.ui.component.overlay

import android.os.Build
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

private val MenuCornerRadius = 22.dp
private val MenuWindowMargin = 12.dp
private val MenuAnchorGap = 6.dp
private val MenuMinWidth = 200.dp
private val MenuMaxWidth = 288.dp
private val MenuBlurRadius = 20.dp
private val MenuContentBlur = 4.dp
// 和弹窗同一套磨砂，菜单项文字优先可读
private const val MenuTintLight = 0.74f
private const val MenuTintDark = 0.8f

// 参照 FlClash：按钮像液滴一样拉伸成卡片，收起时沿原路缩回按钮
private const val MorphOpenDamping = 0.754f
private const val MorphOpenStiffness = 400f
private const val MorphCloseDamping = 0.82f
private const val MorphCloseStiffness = 300f
// 内容在卡片长到三成后才淡入，变形初段只看得到一颗玻璃液滴
private const val ContentRevealStart = 0.3f
// 液滴在离开按钮的最初一段淡入，收起时最后一段淡出
private const val CardFadeSpan = 0.22f

/**
 * Metaball-style menu after FlClash: the anchor turns into a glass capsule that stretches like a
 * droplet into the frosted card, the items fading in from a slight blur; closing reverses into
 * the anchor. The popup spans the window so the card can travel between the anchor and its slot
 */
@Composable
internal fun EnhancedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    content: @Composable ColumnScope.() -> Unit
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (!visibleState.currentState && !visibleState.targetState) return

    val frostedSnapshot = rememberFrostedSnapshot(enabled = true)
    val hostView = LocalView.current
    val geometry = remember { MenuMorphGeometry() }
    val positionProvider = remember(geometry, hostView) {
        WindowFillingPositionProvider { anchorInWindow, direction ->
            val origin = IntArray(2).also { hostView.rootView.getLocationOnScreen(it) }
            geometry.anchorOnScreen = Rect(
                anchorInWindow.left + origin[0].toFloat(),
                anchorInWindow.top + origin[1].toFloat(),
                anchorInWindow.right + origin[0].toFloat(),
                anchorInWindow.bottom + origin[1].toFloat()
            )
            geometry.ltr = direction == LayoutDirection.Ltr
        }
    }
    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true)
    ) {
        EnhancedMenuMorphSurface(
            visibleState = visibleState,
            geometry = geometry,
            offset = offset,
            frostedSnapshot = frostedSnapshot,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = content
        )
    }
}

private class MenuMorphGeometry {
    var anchorOnScreen by mutableStateOf<Rect?>(null)
    var popupOriginOnScreen by mutableStateOf<Offset?>(null)
    var ltr = true
}

/** Puts the popup over the whole window; the card places itself and reports the anchor. */
private class WindowFillingPositionProvider(
    private val onAnchor: (IntRect, LayoutDirection) -> Unit
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        onAnchor(anchorBounds, layoutDirection)
        return IntOffset.Zero
    }
}

@Composable
private fun EnhancedMenuMorphSurface(
    visibleState: MutableTransitionState<Boolean>,
    geometry: MenuMorphGeometry,
    offset: DpOffset,
    frostedSnapshot: FrostedSnapshot?,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = rememberTransition(visibleState, label = "enhanced-menu-morph")
    val morph = transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                spring(MorphOpenDamping, MorphOpenStiffness)
            } else {
                spring(MorphCloseDamping, MorphCloseStiffness)
            }
        },
        label = "enhanced-menu-morph-progress"
    ) { visible -> if (visible) 1f else 0f }

    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val picker = remember(scope, context) {
        EnhancedMenuPicker(scope, context) { visibleState.targetState }
    }
    val highlight = colors.onSurface.copy(alpha = if (dark) 0.12f else 0.075f)
    val glow = if (dark) Color.White.copy(alpha = 0.10f) else colors.onSurface.copy(alpha = 0.06f)
    val cardShape = remember { MorphCornerShape() }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { geometry.popupOriginOnScreen = it.positionOnScreen() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismissRequest() }) }
    ) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Box(
                    Modifier
                        .layoutId(MorphBackgroundId)
                        .shadow(
                            elevation = 22.dp,
                            shape = cardShape,
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.18f)
                        )
                        .clip(cardShape)
                        // 卡片空白处的点按不应穿透到外层，触发关闭
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    FrostedCardBackground(
                        snapshot = frostedSnapshot,
                        shape = cardShape,
                        solidColor = colors.surfaceContainerHigh.copy(alpha = if (dark) 0.97f else 0.98f),
                        frostTint = colors.surfaceContainerHigh.copy(alpha = if (dark) MenuTintDark else MenuTintLight),
                        blurRadius = MenuBlurRadius
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .menuSheen(dark)
                            .border(
                                width = 0.5.dp,
                                color = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f),
                                shape = cardShape
                            )
                    )
                }
                Column(
                    modifier = modifier
                        .layoutId(MorphContentId)
                        .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
                        .width(IntrinsicSize.Max)
                        .onGloballyPositioned { picker.container = it }
                        .enhancedMenuPickGesture(picker) { scrollState.maxValue == 0 }
                        .drawEnhancedMenuHighlight(picker, highlight, glow)
                        .verticalScroll(scrollState)
                        .padding(vertical = 6.dp)
                ) {
                    CompositionLocalProvider(LocalEnhancedMenuPicker provides picker) {
                        content()
                    }
                }
            }
        ) { measurables, constraints ->
            val window = IntSize(constraints.maxWidth, constraints.maxHeight)
            val margin = MenuWindowMargin.roundToPx()
            val contentPlaceable = measurables.first { it.layoutId == MorphContentId }.measure(
                Constraints(maxWidth = window.width - margin * 2, maxHeight = window.height - margin * 2)
            )
            val menuSize = IntSize(contentPlaceable.width, contentPlaceable.height)
            val anchor = geometry.localAnchor()
            if (anchor == null || menuSize.width <= 0 || menuSize.height <= 0) {
                return@Layout layout(window.width, window.height) {}
            }
            val slot = placeEnhancedMenu(
                anchor = anchor,
                window = window,
                menu = menuSize,
                ltr = geometry.ltr,
                spacing = menuSpacing(this, offset)
            )
            val progress = morph.value
            val frame = morphFrame(anchor, slot, menuSize, progress)
            cardShape.cornerRadius = lerp(
                anchor.height / 2f,
                MenuCornerRadius.toPx(),
                progress.coerceIn(0f, 1f)
            )
            val background = measurables.first { it.layoutId == MorphBackgroundId }.measure(
                Constraints.fixed(frame.width.coerceAtLeast(1), frame.height.coerceAtLeast(1))
            )
            val scale = minOf(
                frame.width / menuSize.width.toFloat(),
                frame.height / menuSize.height.toFloat()
            ).coerceAtLeast(0f)
            val contentAlpha = ((progress - ContentRevealStart) / (1f - ContentRevealStart)).coerceIn(0f, 1f)
            val cardAlpha = (progress / CardFadeSpan).coerceIn(0f, 1f)
            val contentBlurPx = MenuContentBlur.toPx() * (1f - contentAlpha)
            layout(window.width, window.height) {
                background.placeWithLayer(frame.left, frame.top) { alpha = cardAlpha }
                val contentX = frame.left + ((frame.width - menuSize.width * scale) / 2f).roundToInt()
                val contentY = frame.top + ((frame.height - menuSize.height * scale) / 2f).roundToInt()
                contentPlaceable.placeWithLayer(contentX, contentY) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                    alpha = contentAlpha * cardAlpha
                    renderEffect = if (contentBlurPx > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BlurEffect(contentBlurPx, contentBlurPx, TileMode.Decal)
                    } else {
                        null
                    }
                }
            }
        }
    }
}

private const val MorphBackgroundId = "menu-morph-background"
private const val MorphContentId = "menu-morph-content"

private fun MenuMorphGeometry.localAnchor(): IntRect? {
    val anchor = anchorOnScreen ?: return null
    val origin = popupOriginOnScreen ?: return null
    return IntRect(
        (anchor.left - origin.x).roundToInt(),
        (anchor.top - origin.y).roundToInt(),
        (anchor.right - origin.x).roundToInt(),
        (anchor.bottom - origin.y).roundToInt()
    )
}

/** Window margin, anchor gap and the caller's offset, in px. */
internal data class MenuSpacing(val margin: Int, val gap: Int, val offsetX: Int, val offsetY: Int)

private fun menuSpacing(density: Density, offset: DpOffset): MenuSpacing = with(density) {
    MenuSpacing(
        margin = MenuWindowMargin.roundToPx(),
        gap = MenuAnchorGap.roundToPx(),
        offsetX = offset.x.roundToPx(),
        offsetY = offset.y.roundToPx()
    )
}

/**
 * Slot of the menu card: below its anchor (above when it does not fit), start-aligned and flipped
 * to end-aligned near the edge, clamped into the window margin
 */
internal fun placeEnhancedMenu(
    anchor: IntRect,
    window: IntSize,
    menu: IntSize,
    ltr: Boolean,
    spacing: MenuSpacing
): IntRect {
    val width = menu.width.coerceAtLeast(1)
    val height = menu.height.coerceAtLeast(1)
    val offsetX = if (ltr) spacing.offsetX else -spacing.offsetX
    val startAligned = if (ltr) anchor.left + offsetX else anchor.right - width + offsetX
    val endAligned = if (ltr) anchor.right - width + offsetX else anchor.left + offsetX
    val maxX = (window.width - spacing.margin - width).coerceAtLeast(spacing.margin)
    val x = listOf(startAligned, endAligned).firstOrNull { it in spacing.margin..maxX }
        ?: startAligned.coerceIn(spacing.margin, maxX)

    val below = anchor.bottom + spacing.gap + spacing.offsetY
    val above = anchor.top - spacing.gap - height - spacing.offsetY
    val maxY = (window.height - spacing.margin - height).coerceAtLeast(spacing.margin)
    val y = when {
        below <= maxY -> below
        above >= spacing.margin -> above
        else -> below.coerceIn(spacing.margin, maxY)
    }
    return IntRect(x, y, x + width, y + height)
}

/**
 * Card frame at [progress]: the anchor rectangle at 0, the menu slot at 1, and past 1 a spring
 * overshoot that keeps growing about the slot's centre instead of drifting away from it
 */
internal fun morphFrame(anchor: IntRect, slot: IntRect, menu: IntSize, progress: Float): IntRect {
    if (progress > 1f) {
        val grow = progress - 1f
        val dx = (menu.width * grow / 2f).roundToInt()
        val dy = (menu.height * grow / 2f).roundToInt()
        return IntRect(slot.left - dx, slot.top - dy, slot.right + dx, slot.bottom + dy)
    }
    val t = progress.coerceAtLeast(0f)
    return IntRect(
        lerp(anchor.left.toFloat(), slot.left.toFloat(), t).roundToInt(),
        lerp(anchor.top.toFloat(), slot.top.toFloat(), t).roundToInt(),
        lerp(anchor.right.toFloat(), slot.right.toFloat(), t).roundToInt(),
        lerp(anchor.bottom.toFloat(), slot.bottom.toFloat(), t).roundToInt()
    )
}

/** Rounded rectangle whose radius follows the morph, from capsule to card corners. */
private class MorphCornerShape : Shape {
    var cornerRadius = 0f

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val radius = cornerRadius.coerceIn(0f, size.minDimension / 2f)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
}

/** Light catching the top of the menu, so the container reads as a lifted sheet of glass. */
private fun Modifier.menuSheen(dark: Boolean): Modifier = drawWithCache {
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.06f else 0.3f),
        0.18f to Color.Transparent
    )
    onDrawBehind { drawRect(sheen) }
}
