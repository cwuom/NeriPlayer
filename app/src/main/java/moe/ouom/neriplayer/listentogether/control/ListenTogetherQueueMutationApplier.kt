package moe.ouom.neriplayer.listentogether.control

import moe.ouom.neriplayer.listentogether.playback.LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE
import moe.ouom.neriplayer.listentogether.protocol.LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueMutation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueOperation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueReference
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack

internal data class ListenTogetherQueueMutationResult(
    val queue: List<ListenTogetherTrack>,
    val currentIndex: Int,
    val targetCurrentIndex: Int?,
    val currentRemoved: Boolean
)

internal fun applyListenTogetherQueueMutation(
    roomQueue: List<ListenTogetherTrack>,
    roomCurrentIndex: Int,
    mutation: ListenTogetherQueueMutation,
    targetCurrentStableKey: String? = null
): ListenTogetherQueueMutationResult {
    if (mutation.operations.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS) {
        return unchangedMutationResult(roomQueue, roomCurrentIndex)
    }
    val references = buildReferenceMap(roomQueue)
    val nextQueue = roomQueue.toMutableList()
    val previousCurrent = nextQueue.getOrNull(roomCurrentIndex)
    val targetCurrent = resolveReference(mutation.targetCurrent, references)

    mutation.operations.forEach { operation ->
        when (operation.type) {
            "remove", "move" -> applyRemoveOrMove(
                operation = operation,
                nextQueue = nextQueue,
                references = references,
                move = operation.type == "move"
            )

            "remove_many" -> operation.order.orEmpty().forEach { reference ->
                removeReference(nextQueue, resolveReference(reference, references))
            }

            "insert" -> applyInsert(operation, nextQueue, references)
            "reorder" -> applyReorder(operation, nextQueue, references)
        }
    }

    if (nextQueue.isEmpty()) {
        return ListenTogetherQueueMutationResult(
            queue = emptyList(),
            currentIndex = -1,
            targetCurrentIndex = null,
            currentRemoved = previousCurrent != null
        )
    }

    val targetCurrentIndex = targetCurrent?.let(nextQueue::indexOfIdentity)
        ?.takeIf { it >= 0 }
    val currentCandidate = when {
        previousCurrent != null && nextQueue.containsIdentity(previousCurrent) -> previousCurrent
        targetCurrent != null && nextQueue.containsIdentity(targetCurrent) -> targetCurrent
        !targetCurrentStableKey.isNullOrBlank() -> nextQueue.firstOrNull {
            it.stableKey == targetCurrentStableKey
        }

        else -> null
    }
    val currentIndex = currentCandidate?.let(nextQueue::indexOfIdentity)
        ?.takeIf { it >= 0 }
        ?: roomCurrentIndex.coerceIn(0, nextQueue.lastIndex)
    return ListenTogetherQueueMutationResult(
        queue = nextQueue,
        currentIndex = currentIndex,
        targetCurrentIndex = targetCurrentIndex,
        currentRemoved = previousCurrent != null && !nextQueue.containsIdentity(previousCurrent)
    )
}

private fun unchangedMutationResult(
    queue: List<ListenTogetherTrack>,
    currentIndex: Int
): ListenTogetherQueueMutationResult {
    return ListenTogetherQueueMutationResult(
        queue = queue,
        currentIndex = if (queue.isEmpty()) -1 else currentIndex.coerceIn(0, queue.lastIndex),
        targetCurrentIndex = null,
        currentRemoved = false
    )
}

private fun buildReferenceMap(
    queue: List<ListenTogetherTrack>
): Map<ListenTogetherQueueReference, ListenTogetherTrack> {
    val occurrences = mutableMapOf<String, Int>()
    val references = mutableMapOf<ListenTogetherQueueReference, ListenTogetherTrack>()
    queue.forEach { track ->
        if (track.stableKey.isBlank()) return@forEach
        val occurrence = occurrences.getOrDefault(track.stableKey, 0)
        occurrences[track.stableKey] = occurrence + 1
        references[ListenTogetherQueueReference(track.stableKey, occurrence)] = track
    }
    return references
}

private fun resolveReference(
    reference: ListenTogetherQueueReference?,
    references: Map<ListenTogetherQueueReference, ListenTogetherTrack>
): ListenTogetherTrack? {
    if (reference == null || reference.stableKey.isBlank() || reference.occurrence < 0) {
        return null
    }
    return references[reference]
}

private fun applyRemoveOrMove(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<ListenTogetherTrack>,
    references: Map<ListenTogetherQueueReference, ListenTogetherTrack>,
    move: Boolean
) {
    val target = resolveReference(operation.target, references) ?: return
    val targetIndex = nextQueue.indexOfIdentity(target)
    if (targetIndex < 0) return
    nextQueue.removeAt(targetIndex)
    if (!move) return
    val anchor = resolveReference(operation.anchor, references)
    val anchorIndex = anchor?.let(nextQueue::indexOfIdentity) ?: -1
    val insertionIndex = when {
        operation.placement == "prepend" -> 0
        operation.placement == "before" && anchorIndex >= 0 -> anchorIndex
        else -> nextQueue.size
    }
    nextQueue.add(insertionIndex, target)
}

private fun applyInsert(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<ListenTogetherTrack>,
    references: Map<ListenTogetherQueueReference, ListenTogetherTrack>
) {
    val track = operation.track?.takeIf { it.stableKey.isNotBlank() } ?: return
    if (nextQueue.size >= LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE) return
    val anchor = resolveReference(operation.anchor, references)
    val anchorIndex = anchor?.let(nextQueue::indexOfIdentity) ?: -1
    val insertionIndex = when {
        operation.placement == "prepend" -> 0
        operation.placement == "before" && anchorIndex >= 0 -> anchorIndex
        else -> nextQueue.size
    }
    nextQueue.add(insertionIndex, track)
}

private fun applyReorder(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<ListenTogetherTrack>,
    references: Map<ListenTogetherQueueReference, ListenTogetherTrack>
) {
    val requestedTracks = operation.order.orEmpty().mapNotNull {
        resolveReference(it, references)
    }
    if (requestedTracks.isEmpty()) return
    val selectedSlots = nextQueue.indices.filter { index ->
        requestedTracks.any { track -> track === nextQueue[index] }
    }
    requestedTracks.forEachIndexed { index, track ->
        selectedSlots.getOrNull(index)?.let { slot -> nextQueue[slot] = track }
    }
}

private fun removeReference(
    queue: MutableList<ListenTogetherTrack>,
    target: ListenTogetherTrack?
) {
    target ?: return
    val index = queue.indexOfIdentity(target)
    if (index >= 0) queue.removeAt(index)
}

private fun List<ListenTogetherTrack>.containsIdentity(
    target: ListenTogetherTrack
): Boolean = indexOfIdentity(target) >= 0

private fun List<ListenTogetherTrack>.indexOfIdentity(
    target: ListenTogetherTrack
): Int = indexOfFirst { it === target }
