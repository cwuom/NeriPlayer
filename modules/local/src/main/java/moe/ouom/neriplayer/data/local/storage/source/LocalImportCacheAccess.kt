package moe.ouom.neriplayer.data.local.storage.source

import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.FileStats

interface LocalImportCacheAccess {
    suspend fun scan(): FileStats
    suspend fun clear(): ExtraCacheClearResult
}
