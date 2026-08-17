package moe.ouom.neriplayer.listentogether.control

import moe.ouom.neriplayer.listentogether.protocol.LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueMutation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueOperation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueReference
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack

internal data class ListenTogetherQueueMutationPlan(
    val mutation: ListenTogetherQueueMutation,
    val requiresSnapshotFallback: Boolean
)

internal fun buildListenTogetherQueueMutationPlan(
    baseState: ListenTogetherRoomState,
    targetQueue: List<ListenTogetherTrack>,
    targetCurrentIndex: Int
): ListenTogetherQueueMutationPlan {
    val baseReferences = referencesForQueue(baseState.queue)
    if (baseReferences.any { it.stableKey.isBlank() } ||
        targetQueue.any { it.stableKey.isBlank() }
    ) {
        return ListenTogetherQueueMutationPlan(
            mutation = ListenTogetherQueueMutation(
                baseRoomVersion = baseState.version.coerceAtLeast(0L),
                operations = emptyList()
            ),
            requiresSnapshotFallback = true
        )
    }

    val baseCounts = baseReferences.groupingBy { it.stableKey }.eachCount()
    val nextTargetOccurrence = mutableMapOf<String, Int>()
    val targetEntries = targetQueue.map { track ->
        val occurrence = nextTargetOccurrence.getOrDefault(track.stableKey, 0)
        nextTargetOccurrence[track.stableKey] = occurrence + 1
        val reference = occurrence.takeIf { it < (baseCounts[track.stableKey] ?: 0) }
            ?.let { ListenTogetherQueueReference(track.stableKey, it) }
        QueueTargetEntry(track = track, reference = reference)
    }
    val targetReferences = targetEntries.mapNotNull { it.reference }
    val targetReferenceSet = targetReferences.toSet()
    val removedReferences = baseReferences.filter { it !in targetReferenceSet }
    val retainedReferences = baseReferences.filter { it in targetReferenceSet }
    val desiredReferences = targetReferences

    val removeOperations = if (
        removedReferences.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS
    ) {
        listOf(
            ListenTogetherQueueOperation(
                type = "remove_many",
                order = removedReferences
            )
        )
    } else {
        removedReferences.map { reference ->
            ListenTogetherQueueOperation(
                type = "remove",
                target = reference
            )
        }
    }
    val moveOperations = buildMoveOperations(retainedReferences, desiredReferences)
    val insertOperations = buildInsertOperations(targetEntries)
    val baseOperationCount = removeOperations.size + insertOperations.size
    val orderOperation = if (
        moveOperations.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS - baseOperationCount
    ) {
        ListenTogetherQueueOperation(
            type = "reorder",
            order = desiredReferences
        )
    } else {
        null
    }
    val orderingOperations = orderOperation?.let(::listOf) ?: moveOperations
    val operations = removeOperations + orderingOperations + insertOperations
    val currentEntry = targetEntries.getOrNull(targetCurrentIndex)
    val mutation = ListenTogetherQueueMutation(
        baseRoomVersion = baseState.version.coerceAtLeast(0L),
        operations = operations,
        targetCurrent = currentEntry?.reference
    )
    return ListenTogetherQueueMutationPlan(
        mutation = mutation,
        requiresSnapshotFallback =
            baseOperationCount > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS ||
                operations.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS
    )
}

private data class QueueTargetEntry(
    val track: ListenTogetherTrack,
    val reference: ListenTogetherQueueReference?
)

private fun referencesForQueue(queue: List<ListenTogetherTrack>): List<ListenTogetherQueueReference> {
    val nextOccurrence = mutableMapOf<String, Int>()
    return queue.map { track ->
        val occurrence = nextOccurrence.getOrDefault(track.stableKey, 0)
        nextOccurrence[track.stableKey] = occurrence + 1
        ListenTogetherQueueReference(track.stableKey, occurrence)
    }
}

private fun buildMoveOperations(
    source: List<ListenTogetherQueueReference>,
    target: List<ListenTogetherQueueReference>
): List<ListenTogetherQueueOperation> {
    if (source == target) return emptyList()
    val working = source.toMutableList()
    val operations = mutableListOf<ListenTogetherQueueOperation>()
    while (
        working != target &&
        operations.size <= LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS
    ) {
        val mismatchIndex = working.indices.firstOrNull { index ->
            working[index] != target.getOrNull(index)
        } ?: break
        val sourceItem = working[mismatchIndex]
        val targetIndex = target.indexOf(sourceItem)
        if (targetIndex > mismatchIndex && tryMoveMatches(working, mismatchIndex, targetIndex, target)) {
            operations += moveOperation(working, mismatchIndex, targetIndex)
            continue
        }
        val targetItem = target.getOrNull(mismatchIndex)
        val sourceIndex = targetItem?.let(working::indexOf) ?: -1
        if (sourceIndex > mismatchIndex && tryMoveMatches(working, sourceIndex, mismatchIndex, target)) {
            operations += moveOperation(working, sourceIndex, mismatchIndex)
            continue
        }
        if (sourceIndex < 0) break
        operations += moveOperation(working, sourceIndex, mismatchIndex)
    }
    return operations
}

private fun tryMoveMatches(
    source: List<ListenTogetherQueueReference>,
    fromIndex: Int,
    toIndex: Int,
    target: List<ListenTogetherQueueReference>
): Boolean {
    val moved = source.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
    return moved == target
}

private fun moveOperation(
    working: MutableList<ListenTogetherQueueReference>,
    fromIndex: Int,
    toIndex: Int
): ListenTogetherQueueOperation {
    val target = working.removeAt(fromIndex)
    val anchor = working.getOrNull(toIndex)
    working.add(toIndex, target)
    return ListenTogetherQueueOperation(
        type = "move",
        target = target,
        anchor = anchor,
        placement = if (anchor == null) "append" else "before"
    )
}

private fun buildInsertOperations(
    targetEntries: List<QueueTargetEntry>
): List<ListenTogetherQueueOperation> {
    val operations = mutableListOf<ListenTogetherQueueOperation>()
    var index = 0
    while (index < targetEntries.size) {
        if (targetEntries[index].reference != null) {
            index++
            continue
        }
        val endExclusive = generateSequence(index) { next ->
            (next + 1).takeIf { it < targetEntries.size }
        }.firstOrNull { candidate -> targetEntries[candidate].reference != null }
            ?: targetEntries.size
        val nextReference = targetEntries.getOrNull(endExclusive)?.reference
        val insertionEntries = targetEntries.subList(index, endExclusive)
        if (nextReference != null) {
            insertionEntries.asReversed().forEach { entry ->
                operations += ListenTogetherQueueOperation(
                    type = "insert",
                    anchor = nextReference,
                    placement = "before",
                    track = entry.track
                )
            }
        } else {
            insertionEntries.forEach { entry ->
                operations += ListenTogetherQueueOperation(
                    type = "insert",
                    placement = "append",
                    track = entry.track
                )
            }
        }
        index = endExclusive
    }
    return operations
}
