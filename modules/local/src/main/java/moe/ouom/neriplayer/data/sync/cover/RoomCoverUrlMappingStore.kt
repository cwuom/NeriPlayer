package moe.ouom.neriplayer.data.sync.cover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class RoomCoverUrlMappingStore(
    private val persistence: CoverMappingPersistence,
    private val legacyReader: LegacyCoverMappingReader,
    private val scheduleCleanup: (String) -> Unit,
    private val reportFailure: (Throwable) -> Unit
) : CoverUrlMappingStore {
    @Volatile
    private var cleanupEligible = true

    override fun load(): Map<String, String> = runBlocking(Dispatchers.IO) {
        val primary = persistence.readIfRoomPrimary()
        if (primary != null) {
            scheduleCleanup("cover-url-mapping-room-load")
            primary
        } else {
            importLegacy()
        }
    }

    private suspend fun importLegacy(): Map<String, String> = when (val legacy = legacyReader.read()) {
        LegacyCoverMappingResult.Missing -> emptyMap()
        is LegacyCoverMappingResult.Loaded -> {
            cleanupEligible = true
            persistence.importLegacyAndPromote(legacy.mappings, cleanupEligible = true)
            scheduleCleanup("cover-url-mapping-import")
            legacy.mappings
        }
        is LegacyCoverMappingResult.Failed -> {
            cleanupEligible = false
            reportFailure(legacy.error)
            emptyMap()
        }
    }

    override fun save(localUrl: String, networkUrl: String) = runBlocking(Dispatchers.IO) {
        persistence.upsert(localUrl, networkUrl, cleanupEligible)
    }

    override fun delete(localUrls: Collection<String>) = runBlocking(Dispatchers.IO) {
        persistence.delete(localUrls, cleanupEligible)
    }
}
