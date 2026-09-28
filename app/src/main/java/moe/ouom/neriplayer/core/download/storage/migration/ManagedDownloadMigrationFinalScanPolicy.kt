package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal fun migrationExpectedCatalogAudioFileNames(
    journal: ManagedMigrationReplacementJournal
): Set<String> {
    val metadataAudioNames = journal.cleanupReceipts.asSequence()
        .filter { receipt -> receipt.sourceSubdirectory == null }
        .mapNotNull { receipt ->
            ManagedDownloadTreeNaming.metadataAudioName(receipt.targetEntry.name)
        }
        .toSet()
    // 已删除源不再有清理收据，裸音频在 SAF 目录也不是可发布的下载条目
    return journal.cleanupReceipts.asSequence()
        .filter { receipt ->
            receipt.sourceSubdirectory == null &&
                receipt.sourceName.substringAfterLast('.', "").lowercase() in audioExtensions
        }
        .map { receipt -> receipt.targetEntry.logicalName }
        .filter(metadataAudioNames::contains)
        .toSet()
}

internal fun shouldRetryAfterMigrationFinalScan(
    outcome: ManagedLibraryRefreshOutcome,
    expectedRootKey: String?,
    minimumSongCount: Int,
    expectedAudioFileNames: Set<String> = emptySet()
): Boolean = outcome !is ManagedLibraryRefreshOutcome.Published ||
    expectedRootKey == null ||
    outcome.rootKey != expectedRootKey ||
    outcome.songCount < minimumSongCount ||
    !outcome.audioFileNames.containsAll(expectedAudioFileNames)
