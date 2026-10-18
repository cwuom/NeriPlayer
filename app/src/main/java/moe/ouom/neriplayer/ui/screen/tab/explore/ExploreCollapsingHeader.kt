package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Header block that scrolls away with the result lists: it collapses before the list moves on
 * the way up, and only comes back once the list has returned to its top, so the header and the
 * list read as one continuous page with no snapping.
 */
@Stable
internal class ExploreCollapsingHeaderState {
    private var measuredHeightPx by mutableIntStateOf(0)
    var heightPx: Int
        get() = measuredHeightPx
        internal set(value) {
            measuredHeightPx = value
            offsetPx = offsetPx.coerceIn(-value.toFloat(), 0f)
        }
    var offsetPx by mutableFloatStateOf(0f)
        private set

    fun expand() {
        offsetPx = 0f
    }

    private fun consume(delta: Float): Float {
        val previous = offsetPx
        offsetPx = (previous + delta).coerceIn(-heightPx.toFloat(), 0f)
        return offsetPx - previous
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset = if (available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero
    }
}

@Composable
internal fun rememberExploreCollapsingHeaderState(): ExploreCollapsingHeaderState =
    remember { ExploreCollapsingHeaderState() }

internal fun Modifier.exploreCollapsingHeader(state: ExploreCollapsingHeaderState): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        if (state.heightPx != placeable.height) state.heightPx = placeable.height
        val offset = state.offsetPx.roundToInt().coerceIn(-placeable.height, 0)
        layout(placeable.width, placeable.height + offset) {
            placeable.placeRelativeWithLayer(0, offset) {
                alpha = if (placeable.height == 0) 1f else {
                    (1f + offset / (placeable.height * 0.7f)).coerceIn(0f, 1f)
                }
            }
        }
    }
