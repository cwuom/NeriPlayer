package moe.ouom.neriplayer.core.download.policy.publication

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

fun DownloadedAudioMetadata.publicationOwnerId(): String? =
    recordedPublicationOwnerId() ?: legacyPublicationOwnerId()

private fun DownloadedAudioMetadata.recordedPublicationOwnerId(): String? =
    listOf(audioPublicationOwnerId, operationId, terminalTemporaryWriteCleanupToken)
        .firstOrNull { !it.isNullOrBlank() }

private fun DownloadedAudioMetadata.legacyPublicationOwnerId(): String? =
    stableKey?.takeIf(String::isNotBlank)?.let { "legacy:$it" }
