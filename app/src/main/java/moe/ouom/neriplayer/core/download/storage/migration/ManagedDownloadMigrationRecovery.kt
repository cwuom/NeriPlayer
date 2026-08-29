package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.audioExtensions

internal fun migrationSourceEntryCount(
    sourceEntries: Iterable<ManagedMigrationSourceEntry>,
    cleanupReceipts: Iterable<ManagedMigrationCleanupReceipt>
): Int {
    val references = buildSet {
        sourceEntries.forEach { entry ->
            entry.sourceReference.trim().takeIf(String::isNotBlank)?.let(::add)
        }
        cleanupReceipts.forEach { receipt ->
            receipt.sourceReference.trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }
    return references.size
}

/**
 * pure recovery decisions for copy receipts whose source disappeared before a
 * cleanup receipt was committed
 */
internal data class ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan(
    val journal: ManagedMigrationReplacementJournal,
    val promoteCandidates: List<ManagedMigrationCopyReceipt>,
    val rollbackCandidates: List<ManagedMigrationCopyReceipt>,
    val preserveCandidates: List<ManagedMigrationCopyReceipt>,
    val deletedSourceAudioCount: Int
) {
    val updatedJournal: ManagedMigrationReplacementJournal
        get() = journal
}

/**
 * Plans recovery for receipts loaded from the checkpoint store. The caller is
 * responsible for fresh target validation and for applying the selected
 * rollback or promotion; this function only reconciles durable state.
 */
internal fun planDeletedSourceCopyReceiptRecovery(
    journal: ManagedMigrationReplacementJournal,
    currentSourceReferences: Iterable<String>,
    copyReceipts: Map<String, ManagedMigrationCopyReceipt>
): ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan {
    return planDeletedSourceCopyReceiptRecovery(
        journal = journal,
        currentSourceReferences = currentSourceReferences,
        copyReceipts = copyReceipts.values
    )
}

internal fun planDeletedSourceCopyReceiptRecovery(
    journal: ManagedMigrationReplacementJournal,
    currentSourceReferences: Iterable<String>,
    copyReceipts: Iterable<ManagedMigrationCopyReceipt>
): ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan {
    val currentReferences = currentSourceReferences
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val cleanupReferences = journal.cleanupReceipts
        .asSequence()
        .map { receipt -> receipt.sourceReference.trim() }
        .filter(String::isNotBlank)
        .toSet()
    val journalReferences = buildSet {
        journal.sourceEntries.forEach { entry ->
            entry.sourceReference.trim().takeIf(String::isNotBlank)?.let(::add)
        }
        journal.targetNamesByReference.keys.forEach { reference ->
            reference.trim().takeIf(String::isNotBlank)?.let(::add)
        }
        journal.replacements.forEach { replacement ->
            replacement.sourceReference.trim().takeIf(String::isNotBlank)?.let(::add)
        }
        addAll(cleanupReferences)
    }
    val receiptsByReference = linkedMapOf<String, ManagedMigrationCopyReceipt>()
    copyReceipts.forEach { rawReceipt ->
        val sourceReference = rawReceipt.sourceReference.trim()
        if (sourceReference.isBlank()) return@forEach
        val receipt = if (rawReceipt.sourceReference == sourceReference) {
            rawReceipt
        } else {
            rawReceipt.copy(sourceReference = sourceReference)
        }
        val previous = receiptsByReference[sourceReference]
        if (previous == null) {
            receiptsByReference[sourceReference] = receipt
        } else if (previous != receipt) {
            throw ManagedDownloadMigrationException.transient(
                "迁移复制凭据存在不一致: $sourceReference"
            )
        }
    }

    val missingReceipts = receiptsByReference.values
        .filter { receipt ->
            receipt.sourceReference in journalReferences &&
                receipt.sourceReference !in currentReferences &&
                receipt.sourceReference !in cleanupReferences
        }
        .sortedWith(
            compareBy<ManagedMigrationCopyReceipt>(
                { it.sourceSubdirectory.orEmpty() },
                { it.sourceName },
                { it.sourceReference }
            )
        )
    if (missingReceipts.isEmpty()) {
        return ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan(
            journal = journal,
            promoteCandidates = emptyList(),
            rollbackCandidates = emptyList(),
            preserveCandidates = emptyList(),
            deletedSourceAudioCount = journal.deletedSourceAudioCount
        )
    }
    val promoteCandidates = missingReceipts.filter { receipt ->
        receipt.sourceAuthoritative &&
            !receipt.createdNew &&
            receipt.replacementBackup == null
    }
    val rollbackCandidates = missingReceipts.filter { receipt ->
        receipt.createdNew || receipt.replacementBackup != null
    }
    val preserveCandidates = missingReceipts.filterNot { receipt ->
        receipt in promoteCandidates || receipt in rollbackCandidates
    }
    val handledReferences = missingReceipts
        .mapTo(HashSet(), ManagedMigrationCopyReceipt::sourceReference)
    val remainingSourceEntries = journal.sourceEntries.filterNot { entry ->
        entry.sourceReference.trim() in handledReferences
    }
    val remainingReplacements = journal.replacements.filterNot { replacement ->
        replacement.sourceReference.trim() in handledReferences
    }
    val remainingTargetNames = journal.targetNamesByReference.filterKeys { reference ->
        reference.trim() !in handledReferences
    }
    val additionalDeletedAudioCount = missingReceipts.count { receipt ->
        receipt.sourceSubdirectory == null &&
            receipt.sourceName.substringAfterLast('.', "").lowercase() in audioExtensions
    }
    val updatedJournal = journal.copy(
        replacements = remainingReplacements,
        targetNamesByReference = remainingTargetNames,
        sourceEntries = remainingSourceEntries,
        sourceEntryCount = migrationSourceEntryCount(
            sourceEntries = remainingSourceEntries,
            cleanupReceipts = journal.cleanupReceipts
        ),
        sourceEntriesComplete = true,
        deletedSourceAudioCount = saturatedAudioDeletionCount(
            existing = journal.deletedSourceAudioCount,
            additional = additionalDeletedAudioCount
        )
    )
    return ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan(
        journal = updatedJournal,
        promoteCandidates = promoteCandidates,
        rollbackCandidates = rollbackCandidates,
        preserveCandidates = preserveCandidates,
        deletedSourceAudioCount = updatedJournal.deletedSourceAudioCount
    )
}

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

internal fun shouldUseDirectMigrationReceiptValidation(
    usePersistedManifest: Boolean,
    persistedReceiptCount: Int
): Boolean {
    return usePersistedManifest && persistedReceiptCount > 0
}

internal fun isMigrationDocumentIdWithinTree(
    treeDocumentId: String,
    documentId: String
): Boolean {
    val root = treeDocumentId.trim()
    val child = documentId.trim()
    if (root.isBlank() || child.isBlank()) return false
    return child == root || child.startsWith("$root/")
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
        ),
        // a legacy journal never proved that its source enumeration was complete
        sourceEntriesComplete = false
    )
}

internal fun reconcileMigrationSourceManifest(
    journal: ManagedMigrationReplacementJournal,
    currentEntries: Iterable<ManagedMigrationEntry>
): ManagedMigrationReplacementJournal {
    val currentEntriesList = currentEntries.toList()
    val currentReferences = currentEntriesList.mapTo(HashSet()) { entry ->
        entry.entry.reference
    }
    val verifiedReferences = journal.cleanupReceipts
        .mapTo(HashSet(), ManagedMigrationCleanupReceipt::sourceReference)
    val currentFingerprints = currentEntriesList.mapTo(HashSet()) { entry ->
        MigrationSourceFingerprint(
            sourceName = entry.entry.name,
            sourceSubdirectory = entry.subdirectory,
            sizeBytes = entry.entry.sizeBytes.coerceAtLeast(0L),
            lastModifiedMs = entry.entry.lastModifiedMs.coerceAtLeast(0L)
        )
    }
    val previouslyKnownEntries = journal.sourceEntries.associateBy(
        ManagedMigrationSourceEntry::sourceReference
    )
    val newlyDeletedAudioCount = if (journal.sourceEntryCountKnown) {
            previouslyKnownEntries
            .filterKeys { reference ->
                reference !in currentReferences && reference !in verifiedReferences
            }
            .values
            .filterNot { entry ->
                MigrationSourceFingerprint(
                    sourceName = entry.sourceName,
                    sourceSubdirectory = entry.sourceSubdirectory,
                    sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = entry.lastModifiedMs.coerceAtLeast(0L)
                ) in currentFingerprints
            }
            .count(ManagedMigrationSourceEntry::isAudioSource)
    } else {
        0
    }
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
    val currentByReference = currentEntriesList.associateBy { it.entry.reference }
    // a complete provider scan is the only point at which a missing source can
    // be interpreted as a user deletion; stale manifest entries are discarded
    // while durable receipts remain authoritative for already verified files
    merged.keys.retainAll { reference ->
        reference in currentByReference ||
            journal.cleanupReceipts.any { receipt -> receipt.sourceReference == reference }
    }
    currentEntriesList.forEach { entry ->
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
        sourceEntryCount = migrationSourceEntryCount(
            sourceEntries = ordered,
            cleanupReceipts = journal.cleanupReceipts
        ),
        sourceEntriesComplete = true,
        deletedSourceAudioCount = saturatedAudioDeletionCount(
            existing = journal.deletedSourceAudioCount,
            additional = newlyDeletedAudioCount
        )
    )
}

private data class MigrationSourceFingerprint(
    val sourceName: String,
    val sourceSubdirectory: String?,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

internal fun removeDeletedMigrationSources(
    journal: ManagedMigrationReplacementJournal,
    deletedReferences: Iterable<String>
): ManagedMigrationReplacementJournal {
    val verifiedReferences = journal.cleanupReceipts
        .mapTo(HashSet(), ManagedMigrationCleanupReceipt::sourceReference)
    val deletions = deletedReferences.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(verifiedReferences::contains)
        .toSet()
    if (deletions.isEmpty()) return journal
    val deletedAudioCount = journal.sourceEntries
        .asSequence()
        .filter { entry -> entry.sourceReference in deletions }
        .count(ManagedMigrationSourceEntry::isAudioSource)
    val sourceEntries = journal.sourceEntries.filterNot { entry ->
        entry.sourceReference in deletions
    }
    return journal.copy(
        replacements = journal.replacements.filterNot { replacement ->
            replacement.sourceReference in deletions
        },
        targetNamesByReference = journal.targetNamesByReference
            .filterKeys { reference -> reference !in deletions },
        sourceEntries = sourceEntries,
        sourceEntryCount = migrationSourceEntryCount(
            sourceEntries = sourceEntries,
            cleanupReceipts = journal.cleanupReceipts
        ),
        sourceEntriesComplete = true,
        deletedSourceAudioCount = saturatedAudioDeletionCount(
            existing = journal.deletedSourceAudioCount,
            additional = deletedAudioCount
        )
    )
}

internal fun ManagedMigrationSourceEntry.isAudioSource(): Boolean {
    return sourceSubdirectory == null &&
        sourceName.substringAfterLast('.', "").lowercase() in audioExtensions
}

internal fun committedMigrationAudioReceiptCount(
    journal: ManagedMigrationReplacementJournal
): Int {
    return journal.cleanupReceipts.count { receipt ->
        receipt.sourceSubdirectory == null &&
            receipt.sourceName.substringAfterLast('.', "").lowercase() in audioExtensions
    }
}

internal fun committedMigrationReceiptsMeetAudioMinimum(
    journal: ManagedMigrationReplacementJournal,
    minimumAudioCount: Int
): Boolean {
    return minimumAudioCount <= 0 ||
        committedMigrationAudioReceiptCount(journal) >= minimumAudioCount
}

internal fun canReuseMigrationTargetDigest(
    expectedSizeBytes: Long,
    actualSizeBytes: Long,
    expectedLastModifiedMs: Long,
    actualLastModifiedMs: Long
): Boolean {
    return expectedSizeBytes > 0L &&
        actualSizeBytes > 0L &&
        expectedSizeBytes == actualSizeBytes &&
        expectedLastModifiedMs > 0L &&
        actualLastModifiedMs > 0L &&
        expectedLastModifiedMs == actualLastModifiedMs
}

private fun saturatedAudioDeletionCount(existing: Int, additional: Int): Int {
    return (existing.coerceAtLeast(0).toLong() + additional.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
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

internal fun mergePersistedMigrationCopyReceipts(
    checkpoints: Iterable<Iterable<ManagedMigrationCopyReceipt>>
): Map<String, ManagedMigrationCopyReceipt> {
    val merged = linkedMapOf<String, ManagedMigrationCopyReceipt>()
    checkpoints.flatten().forEach { receipt ->
        val reference = receipt.sourceReference.trim()
        if (reference.isBlank()) {
            throw ManagedDownloadMigrationException.transient(
                "迁移复制凭据缺少源文档引用"
            )
        }
        val previous = merged[reference]
        if (previous != null && previous != receipt) {
            throw ManagedDownloadMigrationException.transient(
                "迁移复制凭据存在不一致: $reference"
            )
        }
        merged[reference] = receipt
    }
    return merged
}

/**
 * current worker receipts supersede stale checkpoint rows
 * older checkpoints still must agree with each other so provider swaps are
 * never hidden by recovery ordering
 */
internal fun mergePersistedMigrationCopyReceipts(
    current: Iterable<ManagedMigrationCopyReceipt>,
    checkpoints: Iterable<Iterable<ManagedMigrationCopyReceipt>>
): Map<String, ManagedMigrationCopyReceipt> {
    val currentReceipts = mergePersistedMigrationCopyReceipts(listOf(current))
    val persistedReceipts = mergePersistedMigrationCopyReceipts(checkpoints)
    return persistedReceipts.toMutableMap().apply {
        putAll(currentReceipts)
    }
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
