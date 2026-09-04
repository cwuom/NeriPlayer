package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult

internal interface ManagedMigrationEntryReader {
    suspend fun <T> read(
        context: Context,
        entry: ManagedDownloadStorage.StoredEntry,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<Result<T>>
}

internal class InputStreamManagedMigrationEntryReader(
    private val openInputStream: (Context, ManagedDownloadStorage.StoredEntry) -> InputStream?
) : ManagedMigrationEntryReader {
    override suspend fun <T> read(
        context: Context,
        entry: ManagedDownloadStorage.StoredEntry,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<Result<T>> {
        val input = openInputStream(context, entry) ?: return StorageLookupResult.Missing
        return try {
            StorageLookupResult.Found(
                Result.success(input.use { stream -> block(stream) })
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StorageLookupResult.Found(Result.failure(error))
        }
    }
}

internal suspend fun <T> ManagedMigrationEntryReader.readOrThrow(
    context: Context,
    entry: ManagedDownloadStorage.StoredEntry,
    operation: String,
    block: suspend (InputStream) -> T
): T {
    return when (val result = read(context, entry, block)) {
        is StorageLookupResult.Found -> result.value.getOrThrow()
        StorageLookupResult.Missing -> throw ManagedDownloadMigrationException.transient(
            "$operation: missing ${entry.name}"
        )
        StorageLookupResult.PermissionLost -> throw ManagedDownloadMigrationException.transient(
            "$operation: storage permission lost for ${entry.name}"
        )
        is StorageLookupResult.ProviderFailure -> throw ManagedDownloadMigrationException.transient(
            "$operation: provider failure for ${entry.name}",
            result.error
        )
        StorageLookupResult.OutOfScope -> throw ManagedDownloadMigrationException.permanent(
            "$operation: out-of-scope reference for ${entry.name}"
        )
        is StorageLookupResult.Unsupported -> throw ManagedDownloadMigrationException.permanent(
            "$operation: unsupported ${result.operation} for ${entry.name}"
        )
    }
}
