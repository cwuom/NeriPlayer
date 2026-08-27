package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY

internal fun shouldRetryActiveMigrationJournal(
    phase: ManagedMigrationReplacementJournalPhase?,
    sourceRootAvailable: Boolean,
    sourceEntriesEmpty: Boolean
): Boolean {
    if (phase == null || phase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED) {
        return false
    }
    return !sourceRootAvailable || sourceEntriesEmpty
}

internal fun mergePersistedMigrationTargetNames(
    checkpoints: Iterable<Map<String, String>>
): Map<String, String> {
    val merged = sortedMapOf<String, String>()
    checkpoints.forEach { checkpoint ->
        checkpoint.forEach { (rawReference, rawTargetName) ->
            val reference = rawReference.trim()
            val targetName = rawTargetName.trim()
            if (reference.isBlank() || !isSafeMigrationPlanName(targetName)) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移目标计划包含无效条目"
                )
            }
            val previous = merged[reference]
            if (previous != null && previous != targetName) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移目标计划存在不一致: $reference"
                )
            }
            merged[reference] = targetName
        }
    }
    return merged
}

internal fun persistedMigrationJournalTargetNames(
    journal: ManagedMigrationReplacementJournal
): Map<String, String> {
    val replacementNames = journal.replacements.map { replacement ->
        mapOf(replacement.sourceReference to replacement.targetName)
    }
    return mergePersistedMigrationTargetNames(
        listOf(journal.targetNamesByReference) + replacementNames
    )
}

internal fun mergePersistedMigrationReplacementPlan(
    generatedPlan: ManagedMigrationNamePlan,
    persistedJournal: ManagedMigrationReplacementJournal
): ManagedMigrationNamePlan {
    val replacements = generatedPlan.replacementPlansByReference.toMutableMap()
    val seenSources = hashSetOf<String>()
    persistedJournal.replacements.forEach { persisted ->
        val sourceReference = persisted.sourceReference.trim()
        if (!seenSources.add(sourceReference)) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务包含重复源条目: $sourceReference"
            )
        }
        validatePersistedMigrationReplacement(persisted)
        val generatedTargetName = generatedPlan.targetNamesByReference[sourceReference]
            ?: throw ManagedDownloadMigrationException.transient(
                "迁移替换事务源条目暂时缺失: $sourceReference"
            )
        if (generatedTargetName != persisted.targetName) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务目标名称已变化: $sourceReference"
            )
        }
        val current = replacements[sourceReference]
        if (current == null) {
            replacements[sourceReference] = persisted
            return@forEach
        }
        if (
            current.targetName != persisted.targetName ||
            current.groupIdentity != persisted.groupIdentity ||
            current.subdirectory != persisted.subdirectory
        ) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务身份已变化: $sourceReference"
            )
        }
        replacements[sourceReference] = current.copy(backupName = persisted.backupName)
    }
    return generatedPlan.copy(replacementPlansByReference = replacements)
}

internal fun isSafeMigrationPlanName(name: String): Boolean {
    return name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        '/' !in name &&
        '\\' !in name
}

private fun validatePersistedMigrationReplacement(
    replacement: ManagedMigrationReplacementPlan
) {
    if (
        replacement.sourceReference.isBlank() ||
        replacement.groupIdentity.isBlank() ||
        !isSafeMigrationPlanName(replacement.targetName) ||
        !isSafeMigrationPlanName(replacement.backupName) ||
        replacement.targetEntry.isDirectory ||
        replacement.targetEntry.name != replacement.targetName ||
        replacement.subdirectory != null &&
            replacement.subdirectory != COVER_SUBDIRECTORY &&
            replacement.subdirectory != LYRIC_SUBDIRECTORY
    ) {
        throw ManagedDownloadMigrationException.transient(
            "迁移替换事务包含无效计划: ${replacement.sourceReference}"
        )
    }
}
