package moe.ouom.neriplayer.data.sync.remote

import moe.ouom.neriplayer.data.model.sync.SyncLocatedRemoteFile
import java.io.IOException

object SyncFallbackFileReader {
    suspend fun <T> read(
        preferredFileName: String,
        fallbackFileNames: List<String>,
        fetch: suspend (String) -> Result<T>,
        isMissing: (Throwable?) -> Boolean
    ): Result<SyncLocatedRemoteFile<T>?> {
        for (fileName in listOf(preferredFileName) + fallbackFileNames) {
            val result = fetch(fileName)
            if (result.isSuccess) return Result.success(SyncLocatedRemoteFile(fileName, result.getOrThrow()))
            if (!isMissing(result.exceptionOrNull())) {
                return Result.failure(result.exceptionOrNull() ?: IOException("Failed to fetch remote data"))
            }
        }
        return Result.success(null)
    }
}
