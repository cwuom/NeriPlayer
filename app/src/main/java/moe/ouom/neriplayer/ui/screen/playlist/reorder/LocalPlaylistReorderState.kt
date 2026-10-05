package moe.ouom.neriplayer.ui.screen.playlist.reorder

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.screen.playlist.localPlaylistReorderScrollFraction
import moe.ouom.neriplayer.ui.navigation.LocalAppDragOverlay
import moe.ouom.neriplayer.ui.navigation.LocalBottomTabBarBoundsInRoot
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerBoundsInRoot
import org.burnoutcrew.reorderable.DragCancelledAnimation
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.SpringDragCancelledAnimation

@Composable
internal fun rememberLocalPlaylistReorderState(
    onMove: (ItemPosition, ItemPosition) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    canDragOver: ((ItemPosition, ItemPosition) -> Boolean)? = null,
    onDragEnd: ((Int, Int) -> Unit)? = null
): LocalPlaylistReorderState {
    val currentOnMove = rememberUpdatedState(onMove)
    val currentCanDragOver = rememberUpdatedState(canDragOver)
    val currentOnDragEnd = rememberUpdatedState(onDragEnd)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentBottomTabBounds = rememberUpdatedState(LocalBottomTabBarBoundsInRoot.current)
    val currentMiniPlayerBounds = rememberUpdatedState(LocalMiniPlayerBoundsInRoot.current)
    val currentDragOverlayAvailable = rememberUpdatedState(LocalAppDragOverlay.current != null)
    return remember(listState, scope, density) {
        LocalPlaylistReorderState(
            listState = listState,
            scope = scope,
            scrollThreshold = with(density) { PlaylistReorderScrollThreshold.toPx() },
            scrollSpeed = with(density) { PlaylistReorderScrollSpeed.toPx() },
            bottomControlBoundsInRoot = { currentMiniPlayerBounds.value ?: currentBottomTabBounds.value },
            dragOverlayAvailable = { currentDragOverlayAvailable.value },
            onMove = { from, to -> currentOnMove.value(from, to) },
            canDragOver = { target, dragging ->
                currentCanDragOver.value?.invoke(target, dragging) != false
            },
            onDragEnd = { start, end -> currentOnDragEnd.value?.invoke(start, end) }
        )
    }
}

internal class LocalPlaylistReorderState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val scrollThreshold: Float,
    private val scrollSpeed: Float,
    private val bottomControlBoundsInRoot: () -> Rect?,
    private val dragOverlayAvailable: () -> Boolean,
    private val onMove: (ItemPosition, ItemPosition) -> Unit,
    private val canDragOver: (ItemPosition, ItemPosition) -> Boolean,
    private val onDragEnd: (Int, Int) -> Unit
) {
    var draggingItemKey: Any? by mutableStateOf(null)
        private set
    val draggingItemIndex: Int?
        get() = draggingItem()?.index
    val draggingItemTop: Float
        get() = draggingItem()?.let { draggedTop(it) - it.offset } ?: 0f
    val dragCancelledAnimation: DragCancelledAnimation = SpringDragCancelledAnimation()

    private var listOrigin by mutableStateOf(Offset.Zero)
    private var pointerInRoot by mutableStateOf<Offset?>(null)
    private var grabOffset = 0f
    private var startIndex = 0
    private var lastKnownIndex = 0
    private var dragJob: Job? = null
    private var lastUnsettledMove: MoveTarget? = null
    private var pendingMove: PendingMove? = null

    internal fun updateListOrigin(origin: Offset) {
        listOrigin = origin
    }

    internal fun updatePointer(position: Offset?) {
        pointerInRoot = position?.plus(listOrigin)
    }

    internal fun startDrag(key: Any) {
        if (draggingItemKey != null) return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val y = pointerY() ?: return
        grabOffset = y - item.offset - listState.layoutInfo.beforeContentPadding
        startIndex = item.index
        lastKnownIndex = item.index
        lastUnsettledMove = null
        pendingMove = null
        draggingItemKey = key
        dragJob = scope.launch {
            while (currentCoroutineContext().isActive && draggingItemKey == key) {
                // 持拖共用一次滚动会话，外部取消只结束子任务，父任务仍能接续
                coroutineScope {
                    launch { listState.scroll(MutatePriority.UserInput) { dragLoop(key, this) } }.join()
                }
                withFrameNanos { }
            }
        }
    }

    internal fun endDrag() {
        val key = draggingItemKey ?: return
        val endIndex = draggingItemIndex ?: lastKnownIndex
        val offset = Offset(0f, draggingItemTop)
        dragJob?.cancel()
        dragJob = null
        draggingItemKey = null
        lastUnsettledMove = null
        pendingMove = null
        scope.launch { dragCancelledAnimation.dragCancelled(ItemPosition(endIndex, key), offset) }
        onDragEnd(startIndex, endIndex)
    }

    private fun pointerY(): Float? = pointerInRoot?.y?.minus(listOrigin.y)

    private fun draggingItem(): LazyListItemInfo? = draggingItemKey?.let { key ->
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    }

    internal fun itemPositionInRoot(key: Any): Offset? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { item ->
            listOrigin + Offset(0f, (item.offset + listState.layoutInfo.beforeContentPadding).toFloat())
        }

    private fun draggedTop(item: LazyListItemInfo): Float {
        val layout = listState.layoutInfo
        val top = ((pointerY() ?: 0f) - grabOffset - layout.beforeContentPadding)
            .coerceAtLeast(0f)
        if (dragOverlayAvailable()) return top
        // 没有覆盖层的宿主仍在列表内绘制，避免卡片被视口完全裁掉
        val maxTop = (layout.viewportSize.height - layout.afterContentPadding -
            layout.beforeContentPadding - item.size).toFloat().coerceAtLeast(0f)
        return top.coerceAtMost(maxTop)
    }

    private suspend fun dragLoop(key: Any, scrollScope: ScrollScope) {
        var previousFrame = withFrameNanos { it }
        while (currentCoroutineContext().isActive && draggingItemKey == key) {
            val frame = withFrameNanos { it }
            val elapsedSeconds = ((frame - previousFrame) / NanosPerSecond)
                .coerceIn(0f, MaxFrameSeconds)
            previousFrame = frame
            val layout = listState.layoutInfo
            if (layout.orientation != Orientation.Vertical || layout.reverseLayout) continue
            settlePendingMove()
            val item = draggingItem() ?: continue
            lastKnownIndex = item.index
            val y = pointerY() ?: continue
            val fraction = localPlaylistReorderScrollFraction(
                pointerY = y,
                visibleStart = layout.beforeContentPadding.toFloat(),
                visibleEnd = bottomControlBoundsInRoot()?.bottom?.minus(listOrigin.y)
                    ?: layout.viewportSize.height.toFloat(),
                threshold = scrollThreshold
            )
            val target = if (fraction != 0f) edgeTarget(item, fraction) else crossedTarget(item)
            val move = target?.let {
                MoveTarget(item.key, it.key, it.index.compareTo(item.index))
            }
            if (pendingMove == null && target != null && move != lastUnsettledMove) {
                beginMove(item, target, checkNotNull(move))
            }
            if (fraction == 0f) continue
            if (fraction < 0f && !listState.canScrollBackward) continue
            if (fraction > 0f && !listState.canScrollForward) continue
            val maxBackward = (layout.viewportEndOffset - item.offset - 1f).coerceAtLeast(0f)
            val maxForward = (item.offset + item.size - layout.viewportStartOffset - 1f)
                .coerceAtLeast(0f)
            val step = (fraction * scrollSpeed * elapsedSeconds)
                .coerceIn(-maxBackward, maxForward)
            if (step == 0f) continue
            scrollScope.scrollBy(step)
            pendingMove?.takeIf { it.preserveAnchor }?.let {
                it.anchorIndex = listState.firstVisibleItemIndex
                it.anchorOffset = listState.firstVisibleItemScrollOffset
            }
        }
    }

    private fun allowsTarget(item: LazyListItemInfo, target: LazyListItemInfo): Boolean {
        val layout = listState.layoutInfo
        val visibleEnd = (bottomControlBoundsInRoot()?.top?.minus(listOrigin.y)
            ?: layout.viewportEndOffset.toFloat()).coerceAtMost(layout.viewportEndOffset.toFloat())
        // 行动画可能保留已离屏的项目，不能把它们当作手指所在视口的目标
        return target.offset + target.size > layout.viewportStartOffset &&
            target.offset < visibleEnd && target.index != item.index && canDragOver(
            ItemPosition(target.index, target.key), ItemPosition(item.index, item.key)
        )
    }

    private fun edgeTarget(item: LazyListItemInfo, fraction: Float): LazyListItemInfo? {
        val candidates = listState.layoutInfo.visibleItemsInfo.filter { target ->
            allowsTarget(item, target) &&
                if (fraction < 0f) target.index < item.index else target.index > item.index
        }
        return if (fraction < 0f) candidates.minByOrNull { it.index }
        else candidates.maxByOrNull { it.index }
    }

    private fun crossedTarget(item: LazyListItemInfo): LazyListItemInfo? {
        val top = draggedTop(item)
        val bottom = top + item.size
        val candidates = listState.layoutInfo.visibleItemsInfo.filter { target ->
            allowsTarget(item, target) && target.offset < bottom && target.offset + target.size > top
        }
        return when {
            top < item.offset -> candidates
                .filter { it.offset > top && it.offset < item.offset }
                .maxByOrNull { it.offset - top }
            top > item.offset -> candidates
                .filter { it.offset + it.size < bottom && it.offset + it.size > item.offset + item.size }
                .maxByOrNull { bottom - it.offset - it.size }
            else -> null
        }
    }

    private fun beginMove(
        item: LazyListItemInfo,
        target: LazyListItemInfo,
        move: MoveTarget
    ) {
        val layout = listState.layoutInfo
        val anchorIndex = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset
        val preserveAnchor = item.index == anchorIndex || target.index == anchorIndex
        pendingMove = PendingMove(
            move = move,
            targetIndex = target.index,
            originalLayout = layout,
            preserveAnchor = preserveAnchor,
            anchorIndex = anchorIndex,
            anchorOffset = anchorOffset
        )
        if (preserveAnchor) listState.requestScrollToItem(anchorIndex, anchorOffset)
        onMove(ItemPosition(item.index, item.key), ItemPosition(target.index, target.key))
    }

    internal fun updatePresentedOrder(indexOf: (Any) -> Int) {
        val pending = pendingMove ?: return
        if (pending.preserveAnchor && indexOf(pending.move.draggingKey) == pending.targetIndex) {
            // 新展示顺序提交后、测量前固定锚点，避免先画错一帧再跳回来
            listState.requestScrollToItem(pending.anchorIndex, pending.anchorOffset)
        }
    }

    private fun settlePendingMove() {
        val pending = pendingMove ?: return
        val updated = draggingItem()
        if (updated?.index == pending.targetIndex && listState.layoutInfo !== pending.originalLayout) {
            lastKnownIndex = updated.index
            lastUnsettledMove = null
            pendingMove = null
            return
        }
        if (++pending.waitedFrames < MoveLayoutWaitFrames) return
        // 搜索排名可能保留展示位置，手指抖动不应重复修改同一目标的数据顺序
        lastUnsettledMove = pending.move
        pendingMove = null
    }

    private data class MoveTarget(val draggingKey: Any, val targetKey: Any, val direction: Int)

    private data class PendingMove(
        val move: MoveTarget,
        val targetIndex: Int,
        val originalLayout: LazyListLayoutInfo,
        val preserveAnchor: Boolean,
        var anchorIndex: Int,
        var anchorOffset: Int,
        var waitedFrames: Int = 0
    )
}

private val PlaylistReorderScrollThreshold = 48.dp
private val PlaylistReorderScrollSpeed = 1200.dp
private const val NanosPerSecond = 1_000_000_000f
private const val MaxFrameSeconds = 0.032f
private const val MoveLayoutWaitFrames = 30
