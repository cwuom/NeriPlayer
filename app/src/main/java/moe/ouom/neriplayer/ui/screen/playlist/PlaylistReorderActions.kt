package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutEventHandling
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import moe.ouom.neriplayer.data.model.SongIdentity

/**
 * moves selected rows as one block to a one-based final position
 *
 * the position is the first row occupied by the block after the move. Returning
 * null keeps invalid input out of the persistence layer
 */
internal fun <T> moveSelectedItemsToOneBasedPosition(
    items: List<T>,
    selectedIndices: Set<Int>,
    requestedPosition: Int
): List<T>? {
    if (items.isEmpty() || selectedIndices.isEmpty()) return null
    val validIndices = selectedIndices
        .filter { it in items.indices }
        .sorted()
    if (validIndices.isEmpty()) return null

    val remaining = items.filterIndexed { index, _ -> index !in validIndices }
    val maxPosition = remaining.size + 1
    if (requestedPosition !in 1..maxPosition) return null

    val moving = validIndices.map(items::get)
    val insertionIndex = requestedPosition - 1
    return buildList(items.size) {
        addAll(remaining.take(insertionIndex))
        addAll(moving)
        addAll(remaining.drop(insertionIndex))
    }
}

internal fun insertionPositionRange(
    itemCount: Int,
    selectedCount: Int
): IntRange {
    if (itemCount <= 0 || selectedCount <= 0 || selectedCount > itemCount) {
        return IntRange.EMPTY
    }
    return 1..(itemCount - selectedCount + 1)
}

internal fun parseOneBasedInsertionPosition(
    raw: String,
    itemCount: Int,
    selectedCount: Int
): Int? {
    val value = raw.trim().toIntOrNull() ?: return null
    return value.takeIf {
        it in insertionPositionRange(itemCount, selectedCount)
    }
}

internal fun resolveReorderDraggingItemTop(
    itemOffset: Int,
    itemHeight: Int,
    viewportHeight: Int,
    draggingOffset: Float,
    reverseLayout: Boolean
): Float {
    if (itemHeight <= 0 || viewportHeight <= 0 || !draggingOffset.isFinite()) {
        return Float.NaN
    }
    val itemTop = if (reverseLayout) {
        viewportHeight - itemOffset - itemHeight
    } else {
        itemOffset
    }
    return itemTop + draggingOffset
}

internal fun canUndoPlaylistReorder(
    currentOrder: List<SongIdentity>,
    appliedOrder: List<SongIdentity>
): Boolean = currentOrder == appliedOrder

internal fun resolveReorderAutoScrollDelta(
    draggingItemTop: Float,
    draggingItemHeight: Int,
    viewportStart: Int,
    viewportEnd: Int,
    edgeDistance: Float,
    maxPerFrame: Float
): Float {
    if (
        !draggingItemTop.isFinite() ||
        draggingItemHeight <= 0 ||
        viewportEnd <= viewportStart ||
        edgeDistance <= 0f ||
        maxPerFrame <= 0f
    ) {
        return 0f
    }
    val itemBottom = draggingItemTop + draggingItemHeight
    val upperEdge = viewportStart + edgeDistance
    val lowerEdge = viewportEnd - edgeDistance
    return when {
        draggingItemTop < upperEdge -> resolveReorderEdgeVelocity(
            overlap = upperEdge - draggingItemTop,
            edgeDistance = edgeDistance,
            maxPerFrame = maxPerFrame,
            direction = -1f
        )
        itemBottom > lowerEdge -> resolveReorderEdgeVelocity(
            overlap = itemBottom - lowerEdge,
            edgeDistance = edgeDistance,
            maxPerFrame = maxPerFrame,
            direction = 1f
        )
        else -> 0f
    }
}

/**
 * keeps an offscreen drag moving with the last stable edge velocity
 *
 * the reorder library may briefly omit a dragged row from visibleItemsInfo
 * while it is crossing a viewport boundary. Keeping the last velocity avoids
 * making the user hold an exact edge position to continue a long move
 */
internal fun resolveReorderOffscreenContinuation(
    lastDelta: Float,
    maxPerFrame: Float
): Float {
    if (!lastDelta.isFinite() || !maxPerFrame.isFinite() || maxPerFrame <= 0f) {
        return 0f
    }
    return lastDelta.coerceIn(-maxPerFrame, maxPerFrame)
}

/**
 * keeps a drag delta from reaching a list edge that cannot consume it
 *
 * returning the physical delta, rather than the logical scroll argument, keeps
 * the edge velocity calculation independent from reverse layout
 */
internal fun gateReorderScrollDelta(
    delta: Float,
    reverseLayout: Boolean,
    canScrollForward: Boolean,
    canScrollBackward: Boolean
): Float {
    if (!delta.isFinite() || delta == 0f) return 0f
    val scrollArgument = if (reverseLayout) -delta else delta
    return when {
        scrollArgument < 0f && canScrollBackward -> delta
        scrollArgument > 0f && canScrollForward -> delta
        else -> 0f
    }
}

/**
 * keeps the normal glass treatment while preventing a reorder drag from
 * feeding edge deltas into its return spring
 */
@Composable
internal fun rememberReorderOverscrollEffect(
    isDragging: Boolean
): OverscrollEffect? {
    val normalEffect = rememberOverscrollEffect()
    val eventFreeEffect = remember(normalEffect) {
        normalEffect?.withoutEventHandling()
    }
    return if (isDragging) eventFreeEffect else normalEffect
}

private fun resolveReorderEdgeVelocity(
    overlap: Float,
    edgeDistance: Float,
    maxPerFrame: Float,
    direction: Float
): Float {
    val progress = (overlap / edgeDistance).coerceIn(0f, 1f)
    val easedProgress = 1f - (1f - progress) * (1f - progress)
    val minimumPerFrame = 1f.coerceAtMost(maxPerFrame)
    return direction * (minimumPerFrame + (maxPerFrame - minimumPerFrame) * easedProgress)
}
