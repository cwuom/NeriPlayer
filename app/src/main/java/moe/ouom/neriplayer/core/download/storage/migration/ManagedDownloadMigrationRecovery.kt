package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY

internal fun shouldRetryActiveMigrationJournal(
    phase: ManagedMigrationReplacementJournalPhase?,
    sourceRootAvailable: Boolean,
    sourceEntriesEmpty: Boolean,
    cleanupReceiptComplete: Boolean = false,
    sourceEntryCountKnown: Boolean = true,
    sourceEntriesIncomplete: Boolean = false
): Boolean {
    if (phase == null || phase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED) {
        return false
    }
    if (!sourceRootAvailable) return true
    if (!sourceEntryCountKnown) return true
    if (sourceEntriesIncomplete && !cleanupReceiptComplete) return true
    return sourceEntriesEmpty && !cleanupReceiptComplete
}

internal val ManagedMigrationReplacementJournal.legacyUnknownCount: Boolean
    get() =
        phase != ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED &&
            version < CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION &&
            (sourceEntryCount <= 0 || cleanupReceipts.isEmpty())

internal fun upgradeLegacyMigrationReplacementJournal(
    journal: ManagedMigrationReplacementJournal,
    sourceEntryCount: Int
): ManagedMigrationReplacementJournal {
    if (journal.version >= CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION) {
        return journal
    }
    return journal.copy(
        version = CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION,
        sourceEntryCount = maxOf(
            sourceEntryCount.coerceAtLeast(0),
            journal.replacements.size
        )
    )
}

internal fun reconcileMigrationSourceManifest(
    journal: ManagedMigrationReplacementJournal,
    currentEntries: Iterable<ManagedMigrationEntry>
): ManagedMigrationReplacementJournal {
    val merged = linkedMapOf<String, ManagedMigrationSourceEntry>()
    fun add(entry: ManagedMigrationSourceEntry) {
        if (entry.sourceReference.isBlank() || entry.sourceName.isBlank()) return
        merged[entry.sourceReference] = entry
    }
    journal.sourceEntries.forEach(::add)
    journal.cleanupReceipts.forEach { receipt ->
        add(
            ManagedMigrationSourceEntry(
                sourceReference = receipt.sourceReference,
                sourceName = receipt.sourceName,
                sourceSubdirectory = receipt.sourceSubdirectory,
                sizeBytes = receipt.targetEntry.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = receipt.targetEntry.lastModifiedMs.coerceAtLeast(0L)
            )
        )
    }
    val currentByReference = currentEntries.associateBy { it.entry.reference }
    // a complete provider scan is the only point at which a missing source can
    // be interpreted as a user deletion; stale manifest entries are discarded
    // while durable receipts remain authoritative for already verified files
    merged.keys.retainAll { reference ->
        reference in currentByReference ||
            journal.cleanupReceipts.any { receipt -> receipt.sourceReference == reference }
    }
    currentEntries.forEach { entry ->
        add(
            ManagedMigrationSourceEntry(
                sourceReference = entry.entry.reference,
                sourceName = entry.entry.name,
                sourceSubdirectory = entry.subdirectory,
                sizeBytes = entry.entry.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = entry.entry.lastModifiedMs.coerceAtLeast(0L)
            )
        )
    }
    val ordered = merged.values.sortedWith(
        compareBy<ManagedMigrationSourceEntry>(
            { it.sourceSubdirectory.orEmpty() },
            { it.sourceName },
            { it.sourceReference }
        )
    )
    return journal.copy(
        sourceEntries = ordered,
        sourceEntryCount = ordered.size.coerceAtLeast(journal.cleanupReceipts.size)
    )
}

internal fun mergePersistedMigrationCleanupReceipts(
    persisted: Iterable<ManagedMigrationCleanupReceipt>,
    current: Iterable<ManagedMigrationCleanupReceipt>
): List<ManagedMigrationCleanupReceipt> {
    val merged = linkedMapOf<String, ManagedMigrationCleanupReceipt>()
    (persisted.asSequence() + current.asSequence()).forEach { receipt ->
        validateMigrationCleanupReceipt(receipt)
        val previous = merged[receipt.sourceReference]
        if (previous != null) {
            if (
                previous.sourceName != receipt.sourceName ||
                previous.sourceSubdirectory != receipt.sourceSubdirectory ||
                previous.targetEntry.name != receipt.targetEntry.name ||
                previous.targetDigest != receipt.targetDigest
            ) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移清理凭据存在不一致: ${receipt.sourceReference}"
                )
            }
            // keep the durable target identity as the authority; replacing it
            // with a later scan could hide a target document swap
            return@forEach
        }
        merged[receipt.sourceReference] = receipt
    }
    return merged.values.sortedWith(
        compareBy<ManagedMigrationCleanupReceipt>(
            { it.sourceSubdirectory.orEmpty() },
            { it.targetEntry.name },
            { it.sourceReference }
        )
    )
}

internal fun hasCompleteMigrationCleanupReceipts(
    journal: ManagedMigrationReplacementJournal
): Boolean {
    if (
        !journal.sourceEntryCountKnown ||
        journal.cleanupReceipts.size != journal.sourceEntryCount
    ) {
        return false
    }
    val sourceReferences = journal.cleanupReceipts.map { receipt ->
        validateMigrationCleanupReceipt(receipt)
        receipt.sourceReference
    }
    return sourceReferences.size == sourceReferences.toSet().size
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
            ?: if (persistedJournal.cleanupReceipts.any { receipt ->
                receipt.sourceReference == sourceReference
            }) {
                // the source was already removed after its target receipt was
                // committed; the replacement plan no longer needs to be copied
                replacements[sourceReference] = persisted
                return@forEach
            } else {
                if (
                    persistedJournal.sourceEntryCountKnown &&
                    persistedJournal.sourceEntries.none { entry ->
                        entry.sourceReference == sourceReference
                    }
                ) {
                    // the complete source scan no longer contains this entry.
                    // It was deleted by the user before it reached the copy
                    // stage; leave no stale replacement plan to block retry
                    return@forEach
                }
                throw ManagedDownloadMigrationException.transient(
                    "迁移替换事务源条目暂时缺失: $sourceReference"
                )
            }
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

private fun validateMigrationCleanupReceipt(
    receipt: ManagedMigrationCleanupReceipt
) {
    if (
        receipt.sourceReference.isBlank() ||
        receipt.sourceName.isBlank() ||
        !isSafeMigrationPlanName(receipt.targetEntry.name) ||
        receipt.targetEntry.isDirectory ||
        receipt.sourceSubdirectory != null &&
            receipt.sourceSubdirectory != COVER_SUBDIRECTORY &&
            receipt.sourceSubdirectory != LYRIC_SUBDIRECTORY ||
        receipt.targetDigest.length != 64 ||
        receipt.targetDigest.any { character ->
            character !in '0'..'9' && character !in 'a'..'f' && character !in 'A'..'F'
        }
    ) {
        throw ManagedDownloadMigrationException.transient(
            "迁移清理凭据包含无效条目: ${receipt.sourceReference}"
        )
    }
}
