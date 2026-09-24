package moe.ouom.neriplayer.core.download.model

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata

internal fun DownloadedAudioMetadata.publicationOwnerId(): String? =
    audioPublicationOwnerId?.takeIf(String::isNotBlank)
        ?: operationId?.takeIf(String::isNotBlank)
        ?: terminalTemporaryWriteCleanupToken?.takeIf(String::isNotBlank)
        ?: stableKey?.takeIf(String::isNotBlank)?.let { "legacy:$it" }
