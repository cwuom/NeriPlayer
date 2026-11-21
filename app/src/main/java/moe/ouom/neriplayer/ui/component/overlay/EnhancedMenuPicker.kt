package moe.ouom.neriplayer.ui.component.overlay

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

internal val LocalEnhancedMenuPicker = staticCompositionLocalOf<EnhancedMenuPicker?> { null }

private val HighlightInsetX = 6.dp
private val HighlightCorner = 14.dp
private const val HighlightFadeInMillis = 70
private const val HighlightFadeOutMillis = 180
private const val HighlightGlideDamping = 0.86f
private const val HighlightGlideStiffness = 760f
// 绒球光晕：参照 FlClash，按下处 28dp 的柔光随手指走，松手时放大到 4 倍并淡出
private val GlowReach = 28.dp
private const val GlowReleaseGrowth = 4f
private const val GlowReleaseMillis = 320

/**
 * Slide-to-pick state of an enhanced menu: items register their bounds, a single highlight
 * glides between them under the finger, and lifting over an item picks it. Pure draw-phase
 * state, so moving the highlight never recomposes the menu
 */
@Stable
internal class EnhancedMenuPicker(
    private val scope: CoroutineScope,
    private val context: Context,
    private val acceptsInput: () -> Boolean
) {
    class Entry {
        var coordinates: LayoutCoordinates? = null
        var enabled: Boolean = true
        var onClick: () -> Unit = {}
    }

    var container: LayoutCoordinates? = null
    private val entries = LinkedHashSet<Entry>()
    internal val highlightTop = Animatable(0f)
    internal val highlightBottom = Animatable(0f)
    internal val highlightAlpha = Animatable(0f)
    internal var glowCenter by mutableStateOf(Offset.Unspecified)
        private set
    internal val glowAlpha = Animatable(0f)
    internal val glowSpread = Animatable(1f)

    fun pressGlow(position: Offset) {
        glowCenter = position
        scope.launch {
            glowSpread.snapTo(1f)
            glowAlpha.animateTo(1f, spring(dampingRatio = 1f, stiffness = 700f))
        }
    }

    fun moveGlow(position: Offset) {
        glowCenter = position
    }

    fun releaseGlow() {
        scope.launch {
            launch { glowSpread.animateTo(GlowReleaseGrowth, tween(GlowReleaseMillis)) }
            glowAlpha.animateTo(0f, tween(GlowReleaseMillis))
        }
    }

    fun register(entry: Entry) {
        entries += entry
    }

    fun unregister(entry: Entry) {
        entries -= entry
    }

    private fun bounds(entry: Entry): Rect? {
        val host = container ?: return null
        val item = entry.coordinates ?: return null
        if (!host.isAttached || !item.isAttached) return null
        return host.localBoundingBoxOf(item, clipBounds = false)
    }

    /** Enabled item under [position], or null when the finger is outside every item. */
    fun entryAt(position: Offset): Entry? {
        val host = container ?: return null
        if (position.x < 0f || position.x > host.size.width) return null
        return entries.firstOrNull { entry ->
            entry.enabled && bounds(entry)?.let { position.y >= it.top && position.y < it.bottom } == true
        }
    }

    fun showHighlight(entry: Entry?, glide: Boolean) {
        val rect = entry?.let(::bounds)
        scope.launch {
            if (rect == null) {
                highlightAlpha.animateTo(0f, tween(HighlightFadeOutMillis))
                return@launch
            }
            val visible = highlightAlpha.value > 0.01f
            if (glide && visible) {
                launch { highlightTop.animateTo(rect.top, spring(HighlightGlideDamping, HighlightGlideStiffness)) }
                launch { highlightBottom.animateTo(rect.bottom, spring(HighlightGlideDamping, HighlightGlideStiffness)) }
            } else {
                highlightTop.snapTo(rect.top)
                highlightBottom.snapTo(rect.bottom)
            }
            highlightAlpha.animateTo(1f, tween(HighlightFadeInMillis))
        }
    }

    fun hideHighlight() = showHighlight(null, glide = false)

    fun tick() = context.performHapticFeedback(HapticFeedbackEffect.Tick)

    fun canTrack(): Boolean = acceptsInput()
}

/**
 * Observes the menu in the initial pass: a press lights its item, sliding past touch slop takes
 * over from the items' own click handling and lifting picks the item under the finger. When the
 * menu scrolls, sliding is left to the scroll and only the press highlight remains
 */
internal fun Modifier.enhancedMenuPickGesture(
    picker: EnhancedMenuPicker,
    slidePickEnabled: () -> Boolean
): Modifier = pointerInput(picker) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!picker.canTrack()) return@awaitEachGesture
        var hovered = picker.entryAt(down.position)
        picker.showHighlight(hovered, glide = false)
        picker.pressGlow(down.position)
        val canSlide = slidePickEnabled()
        var sliding = false
        var travelled = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (sliding) change.consume()
                break
            }
            travelled += change.positionChange()
            picker.moveGlow(change.position)
            if (!sliding && travelled.getDistance() > viewConfiguration.touchSlop) {
                if (!canSlide) {
                    hovered = null
                    picker.hideHighlight()
                    break
                }
                sliding = true
            }
            if (!sliding) continue
            change.consume()
            val next = picker.entryAt(change.position)
            if (next !== hovered) {
                hovered = next
                if (next != null) picker.tick()
                picker.showHighlight(next, glide = true)
            }
        }
        picker.hideHighlight()
        picker.releaseGlow()
        if (sliding && picker.canTrack()) hovered?.onClick?.invoke()
    }
}

internal fun Modifier.drawEnhancedMenuHighlight(
    picker: EnhancedMenuPicker,
    color: Color,
    glow: Color
): Modifier = drawWithContent {
    val alpha = picker.highlightAlpha.value
    val inset = HighlightInsetX.toPx()
    val top = picker.highlightTop.value
    val height = picker.highlightBottom.value - top
    if (alpha > 0.01f && height > 0f) {
        drawRoundRect(
            color = color.copy(alpha = color.alpha * alpha),
            topLeft = Offset(inset, top),
            size = Size(size.width - inset * 2f, height),
            cornerRadius = CornerRadius(HighlightCorner.toPx())
        )
    }
    drawContent()
    drawMenuGlow(picker, glow)
}

/** Soft spot under the finger, solid at its core and fading out; only drawn while it shows. */
private fun DrawScope.drawMenuGlow(picker: EnhancedMenuPicker, glow: Color) {
    val glowAlpha = picker.glowAlpha.value
    val center = picker.glowCenter
    if (glowAlpha <= 0.01f || !center.isSpecified) return
    val radius = GlowReach.toPx() * picker.glowSpread.value
    val core = glow.copy(alpha = glow.alpha * glowAlpha)
    drawCircle(
        brush = Brush.radialGradient(
            0f to core,
            0.45f to core,
            1f to core.copy(alpha = 0f),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * iOS-style menu row: label first, glyph on the trailing edge. A row with both icons keeps the
 * leading one in front. Highlighting is drawn by the menu, so the row itself has no ripple
 */
@Composable
internal fun EnhancedMenuItem(
    picker: EnhancedMenuPicker,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    enabled: Boolean
) {
    val latestOnClick = rememberUpdatedState(onClick)
    val entry = remember { EnhancedMenuPicker.Entry() }
    SideEffect {
        entry.enabled = enabled
        entry.onClick = { latestOnClick.value() }
    }
    DisposableEffect(picker, entry) {
        picker.register(entry)
        onDispose { picker.unregister(entry) }
    }
    val colors = MaterialTheme.colorScheme
    val contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .onGloballyPositioned { entry.coordinates = it }
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = { latestOnClick.value() }
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (leadingIcon != null && trailingIcon != null) {
                MenuGlyph(leadingIcon)
            }
            Box(Modifier.weight(1f)) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text() }
            }
            (trailingIcon ?: leadingIcon)?.let { MenuGlyph(it) }
        }
    }
}

@Composable
private fun MenuGlyph(icon: @Composable () -> Unit) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
}
