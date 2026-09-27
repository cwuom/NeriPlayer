package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.migration.recovery.mergePersistedMigrationTargetNames

internal fun migrationExpectedAudioFileNames(
    persistedTargetNames: Map<String, String>,
    currentTargetNames: Map<String, String>
): Set<String> = mergePersistedMigrationTargetNames(
    listOf(persistedTargetNames, currentTargetNames)
).values.filter { name ->
    name.substringAfterLast('.', "").lowercase() in audioExtensions
}.toSet()

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
