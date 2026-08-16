package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.Serializable

internal const val LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION = 2
internal const val LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS = 64

@Serializable
data class ListenTogetherQueueReference(
    val stableKey: String,
    val occurrence: Int
)

@Serializable
data class ListenTogetherQueueOperation(
    val type: String,
    val target: ListenTogetherQueueReference? = null,
    val anchor: ListenTogetherQueueReference? = null,
    val placement: String? = null,
    val track: ListenTogetherTrack? = null,
    val order: List<ListenTogetherQueueReference>? = null
)

@Serializable
data class ListenTogetherQueueMutation(
    val baseRoomVersion: Long,
    val operations: List<ListenTogetherQueueOperation>,
    val targetCurrent: ListenTogetherQueueReference? = null
)
