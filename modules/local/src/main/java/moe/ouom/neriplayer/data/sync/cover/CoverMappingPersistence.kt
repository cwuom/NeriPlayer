package moe.ouom.neriplayer.data.sync.cover

internal interface CoverMappingPersistence {
    suspend fun readIfRoomPrimary(): Map<String, String>?
    suspend fun importLegacyAndPromote(mappings: Map<String, String>, cleanupEligible: Boolean)
    suspend fun upsert(localUrl: String, networkUrl: String, cleanupEligible: Boolean)
    suspend fun delete(localUrls: Collection<String>, cleanupEligible: Boolean)
}
