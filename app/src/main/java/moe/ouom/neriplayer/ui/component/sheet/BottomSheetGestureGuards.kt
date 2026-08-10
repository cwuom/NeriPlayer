package moe.ouom.neriplayer.ui.component.sheet

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollFactory
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
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory

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
                val shouldPassToParent = shouldPassBottomSheetMotionToParent(
                    availableY = available.y,
                    allowDownwardToParent = currentAllowDownwardToParent()
                )
                return if (shouldPassToParent) Offset.Zero else available
            }

            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                val shouldPassToParent = shouldPassBottomSheetMotionToParent(
                    availableY = available.y,
                    allowDownwardToParent = currentAllowDownwardToParent()
                )
                return if (shouldPassToParent) Velocity.Zero else available
            }
        }
    }
}

internal fun shouldPassBottomSheetMotionToParent(
    availableY: Float,
    allowDownwardToParent: Boolean
): Boolean = availableY > 0f && allowDownwardToParent

fun Modifier.bottomSheetScrollGuard(
    allowDownwardToParent: (() -> Boolean)? = null
): Modifier = composed {
    if (shouldInstallBottomSheetNestedScrollGuard(
            overscrollFactory = LocalOverscrollFactory.current,
            preserveParentHandoff = allowDownwardToParent != null
        )
    ) {
        nestedScroll(
            rememberBottomSheetNestedScrollConnection(allowDownwardToParent ?: { false })
        )
    } else {
        this
    }
}

internal fun shouldInstallBottomSheetNestedScrollGuard(
    overscrollFactory: OverscrollFactory?,
    preserveParentHandoff: Boolean = false
): Boolean = overscrollFactory !== AdvancedGlassOverscrollFactory || preserveParentHandoff

fun Modifier.bottomSheetDragBlocker(): Modifier = pointerInput(Unit) {
    detectVerticalDragGestures { change, _ ->
        change.consume()
    }
}
