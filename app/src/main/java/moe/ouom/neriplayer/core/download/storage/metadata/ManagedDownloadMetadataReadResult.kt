package moe.ouom.neriplayer.core.download.storage.metadata

import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo

internal sealed interface ManagedMetadataReadResult {
    data class Found(val metadata: DownloadedAudioMetadata) : ManagedMetadataReadResult
    data object Missing : ManagedMetadataReadResult
    data object Malformed : ManagedMetadataReadResult
    data class Unavailable(val error: Throwable) : ManagedMetadataReadResult
}

internal class ManagedMetadataReadUnavailableException(
    val references: Set<String>,
    cause: Throwable
) : IOException("download metadata unavailable: ${references.size}", cause)

internal fun classifyManagedMetadataRead(result: StorageLookupResult<String>): ManagedMetadataReadResult =
    when (result) {
        is StorageLookupResult.Found -> ManagedDownloadMetadataCodec
            .parseDownloadedAudioMetadataJson(result.value)
            ?.let(ManagedMetadataReadResult::Found) ?: ManagedMetadataReadResult.Malformed
        StorageLookupResult.Missing -> ManagedMetadataReadResult.Missing
        is StorageLookupResult.ProviderFailure -> when {
            result.error is CancellationException -> throw result.error
            ManagedDownloadReferenceIo.isMissingDocumentFailure(result.error) -> ManagedMetadataReadResult.Missing
            else -> ManagedMetadataReadResult.Unavailable(result.error)
        }
        StorageLookupResult.PermissionLost -> ManagedMetadataReadResult.Unavailable(
            SecurityException("metadata permission lost")
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> ManagedMetadataReadResult.Unavailable(
            IOException("metadata read unsupported: $result")
        )
    }

internal fun requireAvailableMetadata(results: Map<String, ManagedMetadataReadResult>) {
    val unavailable = results.mapNotNull { (reference, result) ->
        (result as? ManagedMetadataReadResult.Unavailable)?.let { reference to it.error }
    }
    if (unavailable.isNotEmpty()) {
        throw ManagedMetadataReadUnavailableException(unavailable.mapTo(linkedSetOf()) { it.first }, unavailable.first().second)
    }
}
