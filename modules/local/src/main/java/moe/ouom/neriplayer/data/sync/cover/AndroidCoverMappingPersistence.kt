package moe.ouom.neriplayer.data.sync.cover

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.data.local.database.store.CoverUrlMappingRoomStore

internal fun createCoverMappingStore(context: Context, fileName: String): CoverUrlMappingStore {
    val appContext = context.applicationContext ?: context
    return RoomCoverUrlMappingStore(
        AndroidCoverMappingPersistence(CoverUrlMappingRoomStore(NeriUserDataDatabase.getInstance(appContext))),
        LegacyCoverMappingReader(File(appContext.filesDir, fileName)),
        scheduleCleanup = { LegacyJsonCleanupRequests.schedule(appContext, it) },
        reportFailure = { NPLogger.e("CoverUrlMapper", "Failed to import cover URL mappings", it) }
    )
}

internal class AndroidCoverMappingPersistence(private val store: CoverUrlMappingRoomStore) : CoverMappingPersistence {
    override suspend fun readIfRoomPrimary(): Map<String, String>? = store.readIfRoomPrimary()
    override suspend fun importLegacyAndPromote(mappings: Map<String, String>, cleanupEligible: Boolean) =
        store.importLegacyAndPromote(mappings, cleanupEligible)
    override suspend fun upsert(localUrl: String, networkUrl: String, cleanupEligible: Boolean) =
        store.upsert(localUrl, networkUrl, cleanupEligible)
    override suspend fun delete(localUrls: Collection<String>, cleanupEligible: Boolean) = store.delete(localUrls, cleanupEligible)
}
