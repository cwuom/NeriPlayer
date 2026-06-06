package moe.ouom.neriplayer.ui.component

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

@Composable
private fun rememberBottomSheetNestedScrollConnection(
    allowDownwardToParent: () -> Boolean
): NestedScrollConnection {
    val currentAllowDownwardToParent by rememberUpdatedState(allowDownwardToParent)
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset.Zero

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val shouldPassToParent = available.y > 0f && currentAllowDownwardToParent()
                return if (shouldPassToParent) Offset.Zero else available
            }

            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                val shouldPassToParent = available.y > 0f && currentAllowDownwardToParent()
                return if (shouldPassToParent) Velocity.Zero else available
            }
        }
    }
}

fun Modifier.bottomSheetScrollGuard(
    allowDownwardToParent: () -> Boolean = { false }
): Modifier = composed {
    nestedScroll(rememberBottomSheetNestedScrollConnection(allowDownwardToParent))
}

fun Modifier.bottomSheetDragBlocker(): Modifier = pointerInput(Unit) {
    detectVerticalDragGestures { change, _ ->
        change.consume()
    }
}
