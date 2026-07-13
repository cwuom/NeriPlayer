package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal data class ManagedDownloadArtifactRemovalResult(
    val requestedReferences: Set<String> = emptySet(),
    val deletedReferences: Set<String> = emptySet()
)

internal data class ManagedDownloadSongDeleteContext(
    val song: DownloadedSong,
    val storedAudio: ManagedDownloadStorage.StoredEntry?,
    val candidateBaseNames: List<String>,
    val explicitReferences: List<String>,
    val requiredReferences: Set<String>
)

internal data class ManagedDownloadSongDeletePlan(
    val song: DownloadedSong,
    val requestedReferences: Set<String>,
    val requiredReferences: Set<String>
)

internal fun mergeManagedRequestedReferences(
    requestedReferenceGroups: Collection<Set<String>>
): Set<String> {
    return linkedSetOf<String>().apply {
        requestedReferenceGroups.forEach(::addAll)
    }
}

internal fun groupRemainingManagedReferencesByIdentity(
    requestedReferencesByIdentity: Map<String, Set<String>>,
    remainingReferences: Set<String>
): Map<String, Set<String>> {
    if (requestedReferencesByIdentity.isEmpty() || remainingReferences.isEmpty()) {
        return emptyMap()
    }
    return buildMap {
        requestedReferencesByIdentity.forEach { (identity, requestedReferences) ->
            val remainingForIdentity = requestedReferences.intersect(remainingReferences)
            if (remainingForIdentity.isNotEmpty()) {
                put(identity, remainingForIdentity)
            }
        }
    }
}

internal suspend fun resolveUndeletedManagedReferences(
    requestedReferences: Set<String>,
    deletedReferences: Set<String>,
    exists: suspend (String) -> Boolean
): Set<String> {
    if (requestedReferences.isEmpty()) {
        return emptySet()
    }
    return buildSet {
        requestedReferences
            .minus(deletedReferences)
            .forEach { reference ->
                if (exists(reference)) {
                    add(reference)
                }
            }
    }
}
