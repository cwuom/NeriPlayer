package moe.ouom.neriplayer.data.model.youtube.cache

import kotlinx.serialization.Serializable

const val NEWPIPE_FALLBACK_SNAPSHOT_VERSION = 1

@Serializable
data class NewPipeFallbackSnapshot(
    val signature: List<String> = emptyList(),
    val throttling: List<String> = emptyList(),
    val version: Int = NEWPIPE_FALLBACK_SNAPSHOT_VERSION
)
