package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.core.download.model.DownloadedAudioMetadata

fun DownloadedAudioMetadata.publicationOwnerId(): String? =
    audioPublicationOwnerId?.takeIf(String::isNotBlank)
        ?: operationId?.takeIf(String::isNotBlank)
        ?: terminalTemporaryWriteCleanupToken?.takeIf(String::isNotBlank)
        ?: stableKey?.takeIf(String::isNotBlank)?.let { "legacy:$it" }
