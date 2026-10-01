package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.model.ltw.message.queue.LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueMutation
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

internal data class ListenTogetherQueueEventPayload(
    val queue: List<ListenTogetherTrack>?,
    val mutation: ListenTogetherQueueMutation?,
    val legacySnapshot: List<ListenTogetherTrack>?
)

internal fun buildListenTogetherQueueEventPayload(
    room: ListenTogetherRoomState?,
    queue: List<ListenTogetherTrack>,
    currentIndex: Int,
    allowMutation: Boolean = true,
    alwaysIncludeSnapshot: Boolean = true
): ListenTogetherQueueEventPayload {
    val plan = queueMutationPlan(room, queue, currentIndex, allowMutation)
    if (plan != null && !plan.requiresSnapshotFallback) {
        return ListenTogetherQueueEventPayload(null, plan.mutation, queue)
    }
    val includeSnapshot = shouldIncludeSnapshot(alwaysIncludeSnapshot, plan, room)
    return ListenTogetherQueueEventPayload(if (includeSnapshot) queue else null, null, null)
}

private fun shouldIncludeSnapshot(always: Boolean, plan: ListenTogetherQueueMutationPlan?, room: ListenTogetherRoomState?): Boolean =
    always || (plan != null && plan.requiresSnapshotFallback) || usesLegacyQueueSnapshot(room)

private fun queueMutationPlan(room: ListenTogetherRoomState?, queue: List<ListenTogetherTrack>, index: Int, allowed: Boolean): ListenTogetherQueueMutationPlan? {
    if (!allowed || room == null) return null
    if (room.schemaVersion < LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION) return null
    return buildListenTogetherQueueMutationPlan(room, queue, index)
}

internal fun usesLegacyQueueSnapshot(room: ListenTogetherRoomState?): Boolean =
    (room?.schemaVersion ?: 1) < LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION
