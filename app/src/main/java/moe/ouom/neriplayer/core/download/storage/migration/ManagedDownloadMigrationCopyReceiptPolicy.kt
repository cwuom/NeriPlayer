package moe.ouom.neriplayer.core.download.storage.migration

import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.util.Locale
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat

internal data class ReusableMigrationCopyPair(
    val sourceEntry: ManagedMigrationEntry,
    val receipt: ManagedMigrationCopyReceipt,
    val targetEntry: ManagedDownloadStorage.StoredEntry
)

/**
 * receipt reuse requires a fresh, complete source stat; a journal manifest is
 * only a recovery hint and must not be trusted as the current fingerprint
 */
internal fun isCurrentMigrationSourceFingerprint(
    receipt: ManagedMigrationCopyReceipt,
    sourceName: String,
    statResult: StorageLookupResult<StorageStat>
): Boolean {
    val stat = (statResult as? StorageLookupResult.Found)?.value ?: return false
    val actualSizeBytes = stat.sizeBytes ?: return false
    val actualLastModifiedMs = stat.lastModifiedMs ?: return false
    return !stat.isDirectory &&
        stat.displayName == sourceName &&
        receipt.sourceName == sourceName &&
        receipt.sourceSizeBytes > 0L &&
        receipt.sourceLastModifiedMs > 0L &&
        actualSizeBytes > 0L &&
        actualLastModifiedMs > 0L &&
        receipt.sourceSizeBytes == actualSizeBytes &&
        receipt.sourceLastModifiedMs == actualLastModifiedMs
}

internal fun isCurrentMigrationSourceFingerprint(
    receipt: ManagedMigrationCopyReceipt,
    sourceName: String,
    entry: ManagedDownloadStorage.StoredEntry
): Boolean {
    return !entry.isDirectory &&
        entry.name == sourceName &&
        receipt.sourceName == sourceName &&
        receipt.sourceSizeBytes > 0L &&
        receipt.sourceLastModifiedMs > 0L &&
        entry.sizeBytes > 0L &&
        entry.lastModifiedMs > 0L &&
        receipt.sourceSizeBytes == entry.sizeBytes &&
        receipt.sourceLastModifiedMs == entry.lastModifiedMs
}

/** selects resumable copies in one pass over the current source manifest */
internal fun collectReusableMigrationCopyPairs(
    entries: List<ManagedMigrationEntry>,
    persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt>,
    namePlan: ManagedMigrationNamePlan,
    targetIndex: ManagedMigrationTargetIndex
): List<ReusableMigrationCopyPair> {
    return entries.mapNotNull { sourceEntry ->
        val receipt = persistedCopyReceipts[sourceEntry.entry.reference] ?: return@mapNotNull null
        val targetName = namePlan.targetNameFor(sourceEntry.toRef())
        val targetEntry = targetIndex.entryFor(sourceEntry.subdirectory, targetName)
            ?: return@mapNotNull null
        val replacementPlan = namePlan.replacementFor(sourceEntry.toRef())
        if (
            canReuseMigrationCopyReceipt(
                receipt = receipt,
                sourceEntry = sourceEntry.entry,
                sourceSubdirectory = sourceEntry.subdirectory,
                targetName = targetName,
                targetEntry = targetEntry,
                replacementPlan = replacementPlan
            )
        ) {
            ReusableMigrationCopyPair(
                sourceEntry = sourceEntry,
                receipt = receipt,
                targetEntry = targetEntry
            )
        } else {
            null
        }
    }
}

/**
 * A copy receipt is only a fast path when both sides still expose the same
 * stable identity and non-empty provider fingerprints. Unknown fingerprints
 * deliberately fall back to the normal copy and hash path.
 */
internal fun canReuseMigrationCopyReceipt(
    receipt: ManagedMigrationCopyReceipt,
    sourceEntry: ManagedDownloadStorage.StoredEntry,
    sourceSubdirectory: String?,
    targetName: String,
    targetEntry: ManagedDownloadStorage.StoredEntry?,
    replacementPlan: ManagedMigrationReplacementPlan? = null
): Boolean {
    val target = targetEntry ?: return false
    if (
        receipt.sourceReference != sourceEntry.reference ||
        receipt.sourceName != sourceEntry.name ||
        receipt.sourceSubdirectory != sourceSubdirectory ||
        receipt.targetEntry.name != targetName ||
        receipt.targetEntry.isDirectory ||
        target.isDirectory
    ) {
        return false
    }
    if (
        replacementPlan == null && receipt.replacementBackup != null ||
        replacementPlan != null &&
            receipt.replacementBackup?.name?.let { it != replacementPlan.backupName } == true
    ) {
        return false
    }
    if (!sameStoredEntryIdentity(receipt.targetEntry, target)) return false
    if (!sameKnownFingerprint(receipt.sourceSizeBytes, sourceEntry.sizeBytes)) return false
    if (!sameKnownFingerprint(
            receipt.sourceLastModifiedMs,
            sourceEntry.lastModifiedMs
        )
    ) {
        return false
    }
    if (!sameCompatibleFingerprint(receipt.targetEntry.sizeBytes, target.sizeBytes)) {
        return false
    }
    return sameCompatibleFingerprint(
        receipt.targetEntry.lastModifiedMs,
        target.lastModifiedMs
    )
}

private fun sameKnownFingerprint(expected: Long, actual: Long): Boolean {
    return expected > 0L && actual > 0L && expected == actual
}

private fun sameCompatibleFingerprint(expected: Long, actual: Long): Boolean {
    return expected <= 0L || actual <= 0L || expected == actual
}

private fun sameStoredEntryIdentity(
    expected: ManagedDownloadStorage.StoredEntry,
    actual: ManagedDownloadStorage.StoredEntry
): Boolean {
    if (expected.reference.isNotBlank() && expected.reference == actual.reference) return true
    if (expected.mediaUri.isNotBlank() && expected.mediaUri == actual.mediaUri) return true
    if (
        expected.localFilePath?.isNotBlank() == true &&
        expected.localFilePath == actual.localFilePath
    ) {
        return true
    }
    val expectedSafIdentity = sequenceOf(expected.reference, expected.mediaUri)
        .mapNotNull(::safDocumentIdentity)
        .toSet()
    val actualSafIdentity = sequenceOf(actual.reference, actual.mediaUri)
        .mapNotNull(::safDocumentIdentity)
        .toSet()
    return expectedSafIdentity.any(actualSafIdentity::contains)
}

private fun safDocumentIdentity(value: String): String? {
    val uri = runCatching { value.toUri() }.getOrNull()
        ?.takeIf { parsed ->
            parsed.scheme.equals("content", ignoreCase = true) &&
                !parsed.authority.isNullOrBlank()
        }
        ?: return null
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: uri.pathSegments.documentIdFromSafPath()
        ?: uri.pathSegments
            .takeIf { segments -> segments.firstOrNull() == "tree" }
            ?.let { segments ->
                runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                    ?: segments.getOrNull(1)
            }
        ?: return null
    return uri.authority!!.lowercase(Locale.ROOT) + "\u0000" + documentId
}

private fun List<String>.documentIdFromSafPath(): String? {
    return when {
        size >= 4 && this[0] == "tree" && this[2] == "document" -> this[3]
        size >= 2 && this[0] == "document" -> this[1]
        else -> null
    }
}
