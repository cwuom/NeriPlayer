package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal const val DEFAULT_REPLACEMENT_BACKUP_NAMESPACE = "migration"

internal data class ManagedMigrationEntryRef(
    val subdirectory: String?,
    val entry: ManagedDownloadStorage.StoredEntry
)

internal data class ManagedMigrationTargetIndex(
    val rootEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val lyricEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata> = emptyMap()
) {
    private val entriesByReference: Map<String, ManagedDownloadStorage.StoredEntry> by lazy {
        buildMap {
            sequenceOf(rootEntriesByName, coverEntriesByName, lyricEntriesByName)
                .flatMap { entries -> entries.values.asSequence() }
                .forEach { entry ->
                    sequenceOf(entry.reference, entry.mediaUri, entry.localFilePath)
                        .mapNotNull { reference -> reference?.trim()?.takeIf(String::isNotBlank) }
                        .forEach { reference -> putIfAbsent(reference, entry) }
                }
        }
    }
    private val metadataEntriesByCanonicalAudioName:
        Map<String, ManagedDownloadStorage.StoredEntry> by lazy {
            buildMap {
                rootEntriesByName.values.forEach { entry ->
                    val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                        ?: return@forEach
                    val key = ManagedDownloadStorageNaming.canonicalNameKey(audioName)
                    val current = get(key)
                    if (current == null || compareMetadataEntries(entry, current, audioName) < 0) {
                        put(key, entry)
                    }
                }
            }
        }

    fun namesFor(subdirectory: String?): Set<String> {
        return when (subdirectory) {
            null -> rootEntriesByName.keys
            COVER_SUBDIRECTORY -> coverEntriesByName.keys
            LYRIC_SUBDIRECTORY -> lyricEntriesByName.keys
            else -> emptySet()
        }
    }

    fun entryFor(subdirectory: String?, name: String): ManagedDownloadStorage.StoredEntry? {
        return when (subdirectory) {
            null -> rootEntriesByName[name]
            COVER_SUBDIRECTORY -> coverEntriesByName[name]
            LYRIC_SUBDIRECTORY -> lyricEntriesByName[name]
            else -> null
        }
    }

    fun entryByReference(reference: String?): ManagedDownloadStorage.StoredEntry? {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return null
        return entriesByReference[normalized]
    }

    fun metadataEntryForAudioName(audioName: String): ManagedDownloadStorage.StoredEntry? {
        return metadataEntriesByCanonicalAudioName[
            ManagedDownloadStorageNaming.canonicalNameKey(audioName)
        ]
    }

    private fun compareMetadataEntries(
        first: ManagedDownloadStorage.StoredEntry,
        second: ManagedDownloadStorage.StoredEntry,
        audioName: String
    ): Int {
        val firstOrdinal = ManagedDownloadTreeNaming.metadataNameOrdinal(first.name, audioName)
            ?: Int.MAX_VALUE
        val secondAudioName = ManagedDownloadTreeNaming.metadataAudioName(second.name) ?: audioName
        val secondOrdinal = ManagedDownloadTreeNaming.metadataNameOrdinal(second.name, secondAudioName)
            ?: Int.MAX_VALUE
        val ordinalComparison = firstOrdinal.compareTo(secondOrdinal)
        return if (ordinalComparison != 0) ordinalComparison else first.name.compareTo(second.name)
    }
}

internal data class ManagedMigrationNamePlan(
    val targetNamesByReference: Map<String, String>,
    val reusedTargetsByReference: Map<String, ManagedDownloadStorage.StoredEntry> = emptyMap(),
    val conflictsByReference: Map<String, String> = emptyMap(),
    val replacementPlansByReference: Map<String, ManagedMigrationReplacementPlan> = emptyMap()
) {
    fun targetNameFor(entry: ManagedMigrationEntryRef): String {
        return targetNamesByReference[entry.entry.reference] ?: entry.entry.name
    }

    fun reusedTargetFor(entry: ManagedMigrationEntryRef): ManagedDownloadStorage.StoredEntry? {
        return reusedTargetsByReference[entry.entry.reference]
    }

    fun conflictFor(entry: ManagedMigrationEntryRef): String? {
        return conflictsByReference[entry.entry.reference]
    }

    fun replacementFor(entry: ManagedMigrationEntryRef): ManagedMigrationReplacementPlan? {
        return replacementPlansByReference[entry.entry.reference]
    }
}

internal object ManagedDownloadMigrationNamePlanner {
    fun buildNamePlan(
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex,
        sourceMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata> = emptyMap(),
        replacementBackupNamespace: String = DEFAULT_REPLACEMENT_BACKUP_NAMESPACE
    ): ManagedMigrationNamePlan {
        val plannedNames = mutableMapOf<String, String>()
        val reusedTargets = mutableMapOf<String, ManagedDownloadStorage.StoredEntry>()
        val conflicts = mutableMapOf<String, String>()
        val replacementPlans = mutableMapOf<String, ManagedMigrationReplacementPlan>()
        val sourceMetadataEntriesByAudioName = buildSourceMetadataEntryIndex(entries)
        val sourceSidecarIndex = ManagedMigrationSourceSidecarIndex(entries)
        val targetIdentityIndex = ManagedMigrationTargetIdentityIndex(targetIndex)
        val reservationsBySubdirectory = mutableMapOf<String?, ManagedMigrationNameReservations>()
        val replacementTargetNamesBySubdirectory = mutableMapOf<String?, MutableSet<String>>()
        fun reservationsFor(subdirectory: String?): ManagedMigrationNameReservations {
            return reservationsBySubdirectory.getOrPut(subdirectory) {
                ManagedMigrationNameReservations(targetIndex.namesFor(subdirectory))
            }
        }
        val rootReservations = reservationsFor(null)
        val audioEntriesByName = entries
            .filter { it.subdirectory == null && it.entry.extension in audioExtensions }
            .associateBy { it.entry.name }

        audioEntriesByName.values
            .sortedBy { it.entry.name }
            .forEach { audioEntry ->
                val sourceMetadata = sourceMetadataByAudioName[audioEntry.entry.name]
                val identityMatch = sourceMetadata?.let { metadata ->
                    targetIdentityIndex.matchFor(metadata)
                }
                val duplicateTarget = identityMatch?.target
                val replacementTargetReserved = duplicateTarget?.let { target ->
                    replacementTargetNamesBySubdirectory
                        .getOrPut(null) { hashSetOf() }
                        .add(ManagedDownloadStorageNaming.canonicalNameKey(target.name))
                } == true
                if (duplicateTarget != null && replacementTargetReserved) {
                    plannedNames[audioEntry.entry.reference] = duplicateTarget.name
                    replacementPlans[audioEntry.entry.reference] = replacementPlanFor(
                        sourceEntry = audioEntry.entry,
                        targetEntry = duplicateTarget,
                        subdirectory = null,
                        groupIdentity = identityMatch.identity,
                        backupNamespace = replacementBackupNamespace
                    )
                    sourceMetadataEntriesByAudioName[audioEntry.entry.name]?.let { metadataEntry ->
                        targetIndex.metadataEntryForAudioName(duplicateTarget.name)
                            ?.let { targetMetadataEntry ->
                            plannedNames[metadataEntry.entry.reference] = targetMetadataEntry.name
                            replacementPlans[metadataEntry.entry.reference] = replacementPlanFor(
                                sourceEntry = metadataEntry.entry,
                                targetEntry = targetMetadataEntry,
                                subdirectory = null,
                                groupIdentity = identityMatch.identity,
                                backupNamespace = replacementBackupNamespace
                            )
                        }
                    }
                    reserveDuplicateSidecars(
                        sourceMetadata = sourceMetadata,
                        targetMetadata = targetIndex.metadataByAudioName[duplicateTarget.name],
                        sourceSidecarIndex = sourceSidecarIndex,
                        targetIndex = targetIndex,
                        plannedNames = plannedNames,
                        replacementPlans = replacementPlans,
                        groupIdentity = identityMatch.identity,
                        backupNamespace = replacementBackupNamespace
                    )
                    return@forEach
                }
                val targetName = resolvePlannedMigrationName(
                    desiredName = audioEntry.entry.name,
                    sourceEntry = audioEntry.entry,
                    targetEntry = targetIndex.entryFor(null, audioEntry.entry.name),
                    reservations = rootReservations
                )
                plannedNames[audioEntry.entry.reference] = targetName
                sourceMetadataEntriesByAudioName[audioEntry.entry.name]?.let { metadataEntry ->
                    val metadataTargetName = rootReservations.reserve(targetName + METADATA_SUFFIX)
                    plannedNames[metadataEntry.entry.reference] = metadataTargetName
                }
            }

        entries.asSequence()
            .filter { entry ->
                entry.subdirectory == null &&
                    ManagedDownloadTreeNaming.isMetadataName(entry.entry.name) &&
                    entry.entry.reference !in plannedNames
            }
            .sortedBy { entry -> entry.entry.name }
            .forEach { metadataEntry ->
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(metadataEntry.entry.name)
                    ?: return@forEach
                val existingMetadata = targetIndex.metadataEntryForAudioName(audioName)
                if (existingMetadata != null) {
                    plannedNames[metadataEntry.entry.reference] = existingMetadata.name
                    reusedTargets[metadataEntry.entry.reference] = existingMetadata
                } else {
                    plannedNames[metadataEntry.entry.reference] = resolvePlannedMigrationName(
                        desiredName = metadataEntry.entry.name,
                        sourceEntry = metadataEntry.entry,
                        targetEntry = targetIndex.entryFor(null, metadataEntry.entry.name),
                        reservations = rootReservations
                    )
                }
            }

        entries.asSequence()
            .filter { entry -> entry.entry.reference !in plannedNames }
            .sortedWith(
                compareBy<ManagedMigrationEntryRef>(
                    { it.subdirectory.orEmpty() },
                    { it.entry.name },
                    { it.entry.reference }
                )
            )
            .forEach { entry ->
                plannedNames[entry.entry.reference] = resolvePlannedMigrationName(
                    desiredName = entry.entry.name,
                    sourceEntry = entry.entry,
                    targetEntry = targetIndex.entryFor(
                        entry.subdirectory,
                        entry.entry.name
                    ),
                    reservations = reservationsFor(entry.subdirectory)
                )
            }

        return ManagedMigrationNamePlan(
            targetNamesByReference = plannedNames,
            reusedTargetsByReference = reusedTargets,
            conflictsByReference = conflicts,
            replacementPlansByReference = replacementPlans
        )
    }

    fun restorePersistedNamePlan(
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex,
        generatedPlan: ManagedMigrationNamePlan,
        persistedTargetNames: Map<String, String>
    ): ManagedMigrationNamePlan? {
        val restoredTargetNames = collectRestorableTargetNames(
            entries = entries,
            targetIndex = targetIndex,
            persistedTargetNames = persistedTargetNames,
            replacementReferences = generatedPlan.replacementPlansByReference.keys
        )
        if (restoredTargetNames.isEmpty()) return null

        val mergedTargetNames = mergeGeneratedTargetNames(
            entries = entries,
            targetIndex = targetIndex,
            generatedPlan = generatedPlan,
            restoredTargetNames = restoredTargetNames
        )
        val entriesByReference = entries.associateBy { entry -> entry.entry.reference }
        val resumableTargets = restoredTargetNames.mapNotNull { (reference, targetName) ->
            val entry = entriesByReference[reference] ?: return@mapNotNull null
            targetIndex.entryFor(entry.subdirectory, targetName)?.let { targetEntry ->
                reference to targetEntry
            }
        }.toMap()
        val generatedReusedTargets = generatedPlan.reusedTargetsByReference.filter { (reference, target) ->
            reference !in restoredTargetNames && mergedTargetNames[reference] == target.name
        }
        val entryReferences = entries.mapTo(HashSet()) { entry -> entry.entry.reference }
        val resumableReplacements = generatedPlan.replacementPlansByReference
            .filter { (reference, replacement) ->
                reference in entryReferences &&
                    mergedTargetNames[reference] == replacement.targetName
            }
        return generatedPlan.copy(
            targetNamesByReference = mergedTargetNames,
            reusedTargetsByReference = generatedReusedTargets + resumableTargets,
            replacementPlansByReference = resumableReplacements
        )
    }

    private fun collectRestorableTargetNames(
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex,
        persistedTargetNames: Map<String, String>,
        replacementReferences: Set<String> = emptySet()
    ): Map<String, String> {
        val reservedNamesBySubdirectory = mutableMapOf<String?, MutableSet<String>>()
        return buildMap {
            entries.sortedWith(migrationEntryRefComparator).forEach { entry ->
                val reference = entry.entry.reference
                val targetName = persistedTargetNames[reference] ?: return@forEach
                if (!isSafeTargetName(targetName)) return@forEach
                val targetEntry = targetIndex.entryFor(entry.subdirectory, targetName)
                if (
                    targetEntry != null &&
                    reference !in replacementReferences &&
                    hasDefinitePersistedTargetMismatch(entry, targetEntry)
                ) {
                    return@forEach
                }
                val reservedNames = reservedNamesBySubdirectory
                    .getOrPut(entry.subdirectory) { hashSetOf() }
                val canonicalName = ManagedDownloadStorageNaming.canonicalNameKey(targetName)
                if (!reservedNames.add(canonicalName)) return@forEach
                put(reference, targetName)
            }
        }
    }

    private fun mergeGeneratedTargetNames(
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex,
        generatedPlan: ManagedMigrationNamePlan,
        restoredTargetNames: Map<String, String>
    ): Map<String, String> {
        val mergedTargetNames = restoredTargetNames.toMutableMap()
        val sourceMetadataEntries = buildSourceMetadataEntryIndex(entries)
        val restoredNamesBySubdirectory = entries.mapNotNull { entry ->
            restoredTargetNames[entry.entry.reference]?.let { name -> entry.subdirectory to name }
        }.groupBy({ (subdirectory, _) -> subdirectory }, { (_, name) -> name })
        val reservationsBySubdirectory = mutableMapOf<String?, ManagedMigrationNameReservations>()
        fun reservationsFor(subdirectory: String?): ManagedMigrationNameReservations {
            return reservationsBySubdirectory.getOrPut(subdirectory) {
                ManagedMigrationNameReservations(
                    targetIndex.namesFor(subdirectory) + restoredNamesBySubdirectory[subdirectory].orEmpty()
                )
            }
        }
        fun assignGenerated(entry: ManagedMigrationEntryRef, desiredName: String): String {
            val reusedTarget = generatedPlan.reusedTargetFor(entry)
            return if (reusedTarget != null) {
                reusedTarget.name
            } else {
                reservationsFor(entry.subdirectory).reserve(desiredName)
            }
        }

        entries.asSequence()
            .filter { entry -> entry.subdirectory == null && entry.entry.extension in audioExtensions }
            .sortedWith(migrationEntryRefComparator)
            .forEach { audioEntry ->
                val generatedAudioName = generatedPlan.targetNameFor(audioEntry)
                val audioTargetName = mergedTargetNames[audioEntry.entry.reference]
                    ?: assignGenerated(audioEntry, audioEntry.entry.name).also { targetName ->
                        mergedTargetNames[audioEntry.entry.reference] = targetName
                    }
                val metadataEntry = sourceMetadataEntries[audioEntry.entry.name] ?: return@forEach
                if (metadataEntry.entry.reference in mergedTargetNames) return@forEach
                val generatedMetadataTarget = generatedPlan.reusedTargetFor(metadataEntry)
                val desiredMetadataName = if (
                    audioTargetName == generatedAudioName && generatedMetadataTarget != null
                ) {
                    generatedMetadataTarget.name
                } else {
                    audioTargetName + METADATA_SUFFIX
                }
                mergedTargetNames[metadataEntry.entry.reference] =
                    assignGenerated(metadataEntry, desiredMetadataName)
            }

        entries.asSequence()
            .filter { entry -> entry.entry.reference !in mergedTargetNames }
            .sortedWith(migrationEntryRefComparator)
            .forEach { entry ->
                mergedTargetNames[entry.entry.reference] = assignGenerated(
                    entry = entry,
                    desiredName = entry.entry.name
                )
            }
        return mergedTargetNames
    }

    private fun hasDefinitePersistedTargetMismatch(
        source: ManagedMigrationEntryRef,
        target: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        if (ManagedDownloadTreeNaming.isMetadataName(source.entry.name)) return false
        val sourceSize = source.entry.sizeBytes.takeIf { sizeBytes -> sizeBytes > 0L }
        val targetSize = target.sizeBytes.takeIf { sizeBytes -> sizeBytes > 0L }
        return sourceSize != null && targetSize != null && sourceSize != targetSize
    }

    fun isEquivalentMigrationTarget(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        return sourceEntry.reference.isNotBlank() && sourceEntry.reference == targetEntry.reference
    }

    private fun resolvePlannedMigrationName(
        desiredName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        reservations: ManagedMigrationNameReservations
    ): String {
        if (targetEntry != null && isEquivalentMigrationTarget(sourceEntry, targetEntry)) {
            return desiredName
        }
        return reservations.reserve(desiredName)
    }

    private fun isSafeTargetName(targetName: String): Boolean {
        val normalized = targetName.trim()
        return normalized.isNotEmpty() &&
            normalized != "." &&
            normalized != ".." &&
            '/' !in normalized &&
            '\\' !in normalized
    }

    private val migrationEntryRefComparator = compareBy<ManagedMigrationEntryRef>(
        { entry -> entry.subdirectory.orEmpty() },
        { entry -> entry.entry.name },
        { entry -> entry.entry.reference }
    )

    private fun reserveDuplicateSidecars(
        sourceMetadata: ManagedDownloadStorage.DownloadedAudioMetadata,
        targetMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        sourceSidecarIndex: ManagedMigrationSourceSidecarIndex,
        targetIndex: ManagedMigrationTargetIndex,
        plannedNames: MutableMap<String, String>,
        replacementPlans: MutableMap<String, ManagedMigrationReplacementPlan>,
        groupIdentity: String,
        backupNamespace: String
    ) {
        val sourcePaths = sidecarPaths(sourceMetadata)
        val targetPaths = sidecarPaths(targetMetadata)
        sourceSidecarIndex.entriesFor(sourcePaths)
            .forEach { candidate ->
                val targetEntry = targetPaths.asSequence()
                    .mapNotNull(targetIndex::entryByReference)
                    .firstOrNull { entry ->
                        entry.name == candidate.entry.name ||
                            entry.nameWithoutExtension == candidate.entry.nameWithoutExtension
                    }
                if (targetEntry != null) {
                    plannedNames[candidate.entry.reference] = targetEntry.name
                    replacementPlans[candidate.entry.reference] = replacementPlanFor(
                        sourceEntry = candidate.entry,
                        targetEntry = targetEntry,
                        subdirectory = candidate.subdirectory,
                        groupIdentity = groupIdentity,
                        backupNamespace = backupNamespace
                    )
                }
            }
    }

    private fun sidecarPaths(metadata: ManagedDownloadStorage.DownloadedAudioMetadata?): Set<String> {
        if (metadata == null) return emptySet()
        return buildSet {
            metadata.coverPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            metadata.lyricPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            metadata.translatedLyricPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            metadata.romanizedLyricPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun buildSourceMetadataEntryIndex(
        entries: List<ManagedMigrationEntryRef>
    ): Map<String, ManagedMigrationEntryRef> {
        return buildMap {
            entries.forEach { candidate ->
                if (candidate.subdirectory != null) return@forEach
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(candidate.entry.name)
                    ?: return@forEach
                val current = get(audioName)
                val candidateOrdinal = ManagedDownloadTreeNaming.metadataNameOrdinal(
                    candidate.entry.name,
                    audioName
                ) ?: Int.MAX_VALUE
                val currentOrdinal = current?.let { entry ->
                    ManagedDownloadTreeNaming.metadataNameOrdinal(entry.entry.name, audioName)
                        ?: Int.MAX_VALUE
                }
                if (
                    current == null ||
                        candidateOrdinal < requireNotNull(currentOrdinal) ||
                        candidateOrdinal == currentOrdinal && candidate.entry.name < current.entry.name
                ) {
                    put(audioName, candidate)
                }
            }
        }
    }

    private fun replacementPlanFor(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry,
        subdirectory: String?,
        groupIdentity: String,
        backupNamespace: String
    ): ManagedMigrationReplacementPlan {
        val backupToken = ManagedDownloadStorageNaming.stableKeySuffix(
            listOf(
                backupNamespace,
                groupIdentity,
                subdirectory.orEmpty(),
                sourceEntry.reference,
                targetEntry.reference,
                targetEntry.name
            ).joinToString("|")
        ).take(24)
        return ManagedMigrationReplacementPlan(
            sourceReference = sourceEntry.reference,
            groupIdentity = groupIdentity,
            subdirectory = subdirectory,
            targetName = targetEntry.name,
            targetEntry = targetEntry,
            backupName = ".np-migration-backup-$backupToken"
        )
    }
}

private class ManagedMigrationNameReservations(initialNames: Collection<String>) {
    private val canonicalNames = initialNames.mapTo(HashSet(), ManagedDownloadStorageNaming::canonicalNameKey)

    fun reserve(desiredName: String): String {
        return ManagedDownloadStorageNaming.reserveUniqueName(canonicalNames, desiredName)
    }
}

private class ManagedMigrationSourceSidecarIndex(entries: List<ManagedMigrationEntryRef>) {
    private val entriesByLocator: Map<String, List<ManagedMigrationEntryRef>> = buildMap {
        entries.asSequence()
            .filter { entry -> entry.subdirectory != null }
            .forEach { entry ->
                sequenceOf(entry.entry.name, entry.entry.reference)
                    .filter(String::isNotBlank)
                    .distinct()
                    .forEach { locator ->
                        put(locator, get(locator).orEmpty() + entry)
                    }
            }
    }

    fun entriesFor(locators: Set<String>): List<ManagedMigrationEntryRef> {
        return buildSet {
            locators.forEach { locator -> addAll(entriesByLocator[locator].orEmpty()) }
        }.toList()
    }
}

private enum class ManagedMigrationSongIdentityKind {
    STABLE_KEY,
    OPERATION_ID
}

private data class ManagedMigrationSongIdentity(
    val kind: ManagedMigrationSongIdentityKind,
    val primary: String,
    val secondary: String? = null
)

private data class ManagedMigrationIdentityMatch(
    val target: ManagedDownloadStorage.StoredEntry,
    val identity: String
)

private class ManagedMigrationTargetIdentityIndex(targetIndex: ManagedMigrationTargetIndex) {
    private val targetsByIdentity:
        Map<ManagedMigrationSongIdentity, Set<ManagedDownloadStorage.StoredEntry>> = buildMap {
            targetIndex.metadataByAudioName.forEach { (audioName, metadata) ->
                val target = targetIndex.rootEntriesByName[audioName] ?: return@forEach
                migrationSongIdentities(metadata).forEach { identity ->
                    val targets = get(identity).orEmpty().toMutableSet()
                    targets += target
                    put(identity, targets)
                }
            }
        }

    fun matchFor(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): ManagedMigrationIdentityMatch? {
        val identities = migrationSongIdentities(metadata)
        if (identities.isEmpty()) return null
        val candidates = identities.mapNotNull { identity ->
            val targets = targetsByIdentity[identity] ?: return@mapNotNull null
            if (targets.size != 1) return@mapNotNull null
            identity to targets.single()
        }
        val distinctTargets = candidates.map { (_, target) -> target }
            .distinctBy { target -> target.reference.ifBlank { target.name } }
        if (distinctTargets.size != 1) return null
        val selected = candidates
            .filter { (_, target) -> target == distinctTargets.single() }
            .minByOrNull { (identity, _) -> identity.kind.ordinal }
            ?: return null
        return ManagedMigrationIdentityMatch(
            target = selected.second,
            identity = selected.first.asStableString()
        )
    }

    fun targetFor(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): ManagedDownloadStorage.StoredEntry? {
        return matchFor(metadata)?.target
    }
}

private fun migrationSongIdentities(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata
): List<ManagedMigrationSongIdentity> {
    return buildList {
        metadata.stableKey?.trim()?.takeIf(String::isNotBlank)?.let { stableKey ->
            add(ManagedMigrationSongIdentity(ManagedMigrationSongIdentityKind.STABLE_KEY, stableKey))
        }
        metadata.operationId?.trim()?.takeIf(String::isNotBlank)?.let { operationId ->
            add(
                ManagedMigrationSongIdentity(
                    kind = ManagedMigrationSongIdentityKind.OPERATION_ID,
                    primary = operationId
                )
            )
        }
    }
}

private fun ManagedMigrationSongIdentity.asStableString(): String {
    return when (kind) {
        ManagedMigrationSongIdentityKind.STABLE_KEY -> "stableKey:$primary"
        ManagedMigrationSongIdentityKind.OPERATION_ID -> "operationId:$primary"
    }
}
