package moe.ouom.neriplayer.ui.screen.playlist.reorder

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import moe.ouom.neriplayer.ui.navigation.LocalAppDragOverlay

internal fun Modifier.localPlaylistReorderable(state: LocalPlaylistReorderState): Modifier =
    onGloballyPositioned { state.updateListOrigin(it.positionInRoot()) }
        .pointerInput(state) {
            var activePointer: PointerId? = null
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val ownerChange = event.changes.firstOrNull { it.id == activePointer }
                        if (ownerChange != null && !ownerChange.pressed) state.endDrag()
                        val change = ownerChange?.takeIf { it.pressed }
                            ?: event.changes.firstOrNull { it.pressed }
                        activePointer = change?.id
                        if (change == null) state.endDrag()
                        state.updatePointer(change?.position)
                        // 父列表接管持拖，避免重排后的行节点取消手势或普通滚动抢走位移
                        if (state.draggingItemKey != null) change?.consume()
                    }
                }
            } finally {
                state.endDrag()
                state.updatePointer(null)
            }
        }

internal fun Modifier.localPlaylistDetectReorder(
    state: LocalPlaylistReorderState,
    key: Any,
    enabled: Boolean = true
): Modifier = if (!enabled) this else pointerInput(state, key) {
    // 手柄只启动拖拽，行节点因重排取消时不能提前保存
    detectDragGestures(
        onDragStart = { state.startDrag(key) },
        onDrag = { change, _ ->
            if (state.draggingItemKey == key) change.consume()
        }
    )
}

@Composable
internal fun LazyItemScope.LocalPlaylistReorderableItem(
    state: LocalPlaylistReorderState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(isDragging: Boolean) -> Unit
) {
    val isDragging = state.draggingItemKey == key
    val isReturning = state.dragCancelledAnimation.position?.key == key
    val overlay = LocalAppDragOverlay.current
    val overlayOwner = remember(state, key) { Any() }
    val lifted = isDragging || isReturning
    if (overlay != null && lifted) {
        DisposableEffect(overlay, overlayOwner, isDragging) {
            overlay.show(overlayOwner, dragging = isDragging) {
                state.itemPositionInRoot(key)?.plus(
                    Offset(
                        0f,
                        if (state.draggingItemKey == key) state.draggingItemTop
                        else state.dragCancelledAnimation.offset.y
                    )
                )
            }
            onDispose { overlay.clear(overlayOwner) }
        }
    }
    val draggingModifier = when {
        isDragging -> Modifier.zIndex(1f).graphicsLayer { translationY = state.draggingItemTop }
        isReturning -> Modifier.zIndex(1f).graphicsLayer {
            translationY = state.dragCancelledAnimation.offset.y
        }
        else -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
    }
    val drawingModifier = if (overlay != null && lifted) {
        Modifier.drawWithContent {
            if (overlay.owns(overlayOwner)) {
                // 复用原行的绘制命令，覆盖层只重画画面，不复制点击或封面加载逻辑
                overlay.layer.record { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }
    } else Modifier
    Box(modifier.then(draggingModifier).then(drawingModifier)) { content(isDragging) }
}
