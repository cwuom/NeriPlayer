package moe.ouom.neriplayer.ui.component.playback.lan

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

@Composable
internal fun LanGroupVolumeSlider(value: Float, enabled: Boolean, commit: (Float) -> Unit) {
    val mayCommit by rememberUpdatedState(enabled)
    if (!enabled) {
        Slider(value = value, onValueChange = {}, enabled = false)
        return
    }
    var applied by remember(value) { mutableFloatStateOf(value) }
    var draft by remember { mutableStateOf<Float?>(null) }
    val displayed = draft ?: applied
    val layoutDirection = LocalLayoutDirection.current
    val currentValue by rememberUpdatedState(displayed)
    val finish by rememberUpdatedState {
        if (mayCommit) draft?.let {
            applied = it
            commit(it)
        }
        draft = null
    }

    Box {
        Slider(
            value = displayed,
            onValueChange = { draft = it },
            onValueChangeFinished = finish
        )
        // 触屏只接受水平拖动，键盘和无障碍调节仍由 Material 滑块处理
        Box(Modifier.matchParentSize().pointerInput(layoutDirection) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = awaitHorizontalVolumeDrag(down) ?: return@awaitEachGesture
                val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                val valuePerPixel = direction / size.width.coerceAtLeast(1)
                val before = currentValue
                fun preview(x: Float) {
                    draft = (before + (x - down.position.x) * valuePerPixel).coerceIn(0f, 1f)
                }
                start.consume()
                preview(start.position.x)
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.singleOrNull() ?: break
                        if (change.id != start.id || change.isConsumed) break
                        preview(change.position.x)
                        change.consume()
                        if (!change.pressed) {
                            if (draft != before) finish()
                            break
                        }
                    }
                } finally {
                    draft = null
                }
            }
        })
    }
}

private suspend fun AwaitPointerEventScope.awaitHorizontalVolumeDrag(down: PointerInputChange): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    while (true) {
        val change = awaitPointerEvent().changes.singleOrNull() ?: return null
        if (change.id != down.id || !change.pressed || change.isConsumed) return null
        val distance = change.position - down.position
        val x = abs(distance.x)
        val y = abs(distance.y)
        if (y > slop && y >= x) return null
        if (x > slop) return change
    }
}
