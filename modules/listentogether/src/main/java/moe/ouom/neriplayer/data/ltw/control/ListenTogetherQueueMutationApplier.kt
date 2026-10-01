package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.ltw.playback.LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE
import moe.ouom.neriplayer.data.model.ltw.message.queue.LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueMutation
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueOperation
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueReference
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

data class ListenTogetherQueueMutationResult(
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

    mutation.operations.forEach { applyOperation(it, nextQueue, references) }

    if (nextQueue.isEmpty()) {
        return ListenTogetherQueueMutationResult(
            queue = emptyList(),
            currentIndex = -1,
            targetCurrentIndex = null,
            currentRemoved = previousCurrent != null
        )
    }

    val targetCurrentIndex = nextQueue.indexOfExistingTrack(targetCurrent)
    val currentCandidate = resolveCurrentCandidate(nextQueue, previousCurrent, targetCurrent, targetCurrentStableKey)
    val currentIndex = nextQueue.indexOfExistingTrack(currentCandidate)
        ?: roomCurrentIndex.coerceIn(0, nextQueue.lastIndex)
    return ListenTogetherQueueMutationResult(
        queue = nextQueue,
        currentIndex = currentIndex,
        targetCurrentIndex = targetCurrentIndex,
        currentRemoved = wasCurrentRemoved(nextQueue, previousCurrent)
    )
}

private fun applyOperation(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<ListenTogetherTrack>,
    references: Map<ListenTogetherQueueReference, ListenTogetherTrack>
) {
    if (operation.type == "remove" || operation.type == "move") {
        applyRemoveOrMove(operation, nextQueue, references, operation.type == "move")
        return
    }
    if (operation.type == "remove_many") {
        operation.order.orEmpty().forEach { removeReference(nextQueue, resolveReference(it, references)) }
        return
    }
    if (operation.type == "insert") applyInsert(operation, nextQueue, references)
    if (operation.type == "reorder") applyReorder(operation, nextQueue, references)
}

private fun resolveCurrentCandidate(
    queue: List<ListenTogetherTrack>,
    previous: ListenTogetherTrack?,
    target: ListenTogetherTrack?,
    targetStableKey: String?
): ListenTogetherTrack? =
    queue.retainedTrack(previous) ?: queue.retainedTrack(target) ?: queue.trackForStableKey(targetStableKey)

private fun List<ListenTogetherTrack>.retainedTrack(track: ListenTogetherTrack?): ListenTogetherTrack? {
    val candidate = track ?: return null
    return candidate.takeIf(::containsIdentity)
}

private fun List<ListenTogetherTrack>.trackForStableKey(stableKey: String?): ListenTogetherTrack? {
    if (stableKey.isNullOrBlank()) return null
    return firstOrNull { it.stableKey == stableKey }
}

private fun List<ListenTogetherTrack>.indexOfExistingTrack(track: ListenTogetherTrack?): Int? {
    val candidate = track ?: return null
    return indexOfIdentity(candidate).takeIf { it >= 0 }
}

private fun wasCurrentRemoved(queue: List<ListenTogetherTrack>, previous: ListenTogetherTrack?): Boolean =
    previous != null && !queue.containsIdentity(previous)

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
    nextQueue.add(insertionIndex(operation.placement, anchor, nextQueue), target)
}

private fun applyInsert(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<ListenTogetherTrack>,
    references: Map<ListenTogetherQueueReference, ListenTogetherTrack>
) {
    val track = operation.track?.takeIf { it.stableKey.isNotBlank() } ?: return
    if (nextQueue.size >= LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE) return
    val anchor = resolveReference(operation.anchor, references)
    nextQueue.add(insertionIndex(operation.placement, anchor, nextQueue), track)
}

private fun insertionIndex(
    placement: String?,
    anchor: ListenTogetherTrack?,
    queue: List<ListenTogetherTrack>
): Int {
    val anchorIndex = anchor?.let(queue::indexOfIdentity) ?: -1
    return when {
        placement == "prepend" -> 0
        placement == "before" && anchorIndex >= 0 -> anchorIndex
        else -> queue.size
    }
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
