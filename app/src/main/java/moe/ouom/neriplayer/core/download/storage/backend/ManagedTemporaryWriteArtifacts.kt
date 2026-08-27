package moe.ouom.neriplayer.core.download.storage.backend

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TEMPORARY_WRITE_NAME_PREFIX = ".npdl_tmp_v2_"
private const val TEMPORARY_WRITE_NAME_SUFFIX = ".pending"
private const val TEMPORARY_WRITE_TARGET_FINGERPRINT_LENGTH = 16
private const val TEMPORARY_WRITE_NONCE_LENGTH = 16

/**
 * tracks an in-process recoverable write until its temporary document is either
 * committed or its cleanup attempt has finished.
 */
internal class ManagedTemporaryWriteLease internal constructor(
    internal val parentIdentity: String,
    val displayName: String
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            ManagedTemporaryWriteArtifacts.release(parentIdentity, displayName)
        }
    }
}

internal sealed interface ManagedTemporaryWriteCleanupSkipReason {
    data class IncompleteDirectory(val confidence: StorageConfidence) :
        ManagedTemporaryWriteCleanupSkipReason

    data object TargetParentMismatch : ManagedTemporaryWriteCleanupSkipReason
}

internal data class ManagedTemporaryWriteCleanupPlan(
    val candidates: List<StorageStat>,
    val retainedActiveCount: Int,
    val skipReason: ManagedTemporaryWriteCleanupSkipReason? = null
)

internal sealed interface ManagedTemporaryWriteCleanupResult {
    data class Completed(
        val deletedCount: Int,
        val missingCount: Int,
        val retainedActiveCount: Int,
        val failures: List<StorageMutationResult>
    ) : ManagedTemporaryWriteCleanupResult

    data class Skipped(
        val reason: ManagedTemporaryWriteCleanupSkipReason
    ) : ManagedTemporaryWriteCleanupResult
}

/**
 * new temporary names include a compact digest of their root-relative target
 * or durable owner. It lets terminal cleanup select only files from that
 * target after process death, while the in-memory lease prevents a concurrent
 * writer from being selected.
 */
internal object ManagedTemporaryWriteArtifacts {
    private val activeWrites = ConcurrentHashMap.newKeySet<TemporaryWriteIdentity>()

    fun acquire(target: StorageTarget): ManagedTemporaryWriteLease {
        val parentIdentity = parentIdentity(target)
        while (true) {
            val displayName = displayNameFor(target, randomNonce())
            val identity = TemporaryWriteIdentity(parentIdentity, displayName)
            if (activeWrites.add(identity)) {
                return ManagedTemporaryWriteLease(parentIdentity, displayName)
            }
        }
    }

    fun createFile(target: StorageTarget.FileTarget, parent: File): ManagedTemporaryFile {
        repeat(8) {
            val lease = acquire(target)
            val file = File(parent, lease.displayName)
            try {
                Files.createFile(file.toPath())
                return ManagedTemporaryFile(file, lease)
            } catch (_: FileAlreadyExistsException) {
                lease.close()
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        }
        throw IllegalStateException("temporary write name collision")
    }

    fun parentReference(target: StorageTarget): StorageReference = when (target) {
        is StorageTarget.FileTarget -> StorageReference.FileRef(
            normalizedFileParent(target.logicalPath)
        )
        is StorageTarget.SafTarget -> target.parent
    }

    fun displayNameFor(target: StorageTarget, nonce: String): String {
        val normalizedNonce = nonce.lowercase()
        require(normalizedNonce.length == TEMPORARY_WRITE_NONCE_LENGTH) {
            "temporary write nonce length"
        }
        require(normalizedNonce.all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }) {
            "temporary write nonce"
        }
        return targetNamePrefix(target) + normalizedNonce + TEMPORARY_WRITE_NAME_SUFFIX
    }

    fun planTerminalCleanup(
        parent: StorageReference,
        target: StorageTarget,
        snapshot: StorageDirectorySnapshot
    ): ManagedTemporaryWriteCleanupPlan {
        if (referenceIdentity(parent) != parentIdentity(target)) {
            return ManagedTemporaryWriteCleanupPlan(
                candidates = emptyList(),
                retainedActiveCount = 0,
                skipReason = ManagedTemporaryWriteCleanupSkipReason.TargetParentMismatch
            )
        }
        if (snapshot.confidence !is StorageConfidence.Complete) {
            return ManagedTemporaryWriteCleanupPlan(
                candidates = emptyList(),
                retainedActiveCount = 0,
                skipReason = ManagedTemporaryWriteCleanupSkipReason.IncompleteDirectory(
                    snapshot.confidence
                )
            )
        }
        val parentIdentity = parentIdentity(target)
        val targetPrefix = targetNamePrefix(target)
        val matchingEntries = snapshot.entries.filter { entry ->
            !entry.isDirectory && isManagedNameForTarget(entry.displayName, targetPrefix)
        }
        val activeEntries = matchingEntries.filter { entry ->
            isActive(parentIdentity, entry.displayName)
        }
        return ManagedTemporaryWriteCleanupPlan(
            candidates = matchingEntries - activeEntries.toSet(),
            retainedActiveCount = activeEntries.size
        )
    }

    fun isManagedNameForTarget(displayName: String, target: StorageTarget): Boolean {
        return isManagedNameForTarget(displayName, targetNamePrefix(target))
    }

    internal fun release(parentIdentity: String, displayName: String) {
        activeWrites.remove(TemporaryWriteIdentity(parentIdentity, displayName))
    }

    private fun isManagedNameForTarget(displayName: String, targetPrefix: String): Boolean {
        if (!displayName.startsWith(targetPrefix) ||
            !displayName.endsWith(TEMPORARY_WRITE_NAME_SUFFIX)
        ) {
            return false
        }
        val nonceStart = targetPrefix.length
        val nonceEnd = displayName.length - TEMPORARY_WRITE_NAME_SUFFIX.length
        if (nonceEnd - nonceStart != TEMPORARY_WRITE_NONCE_LENGTH) {
            return false
        }
        return displayName.substring(nonceStart, nonceEnd).all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }
    }

    private fun isActive(parentIdentity: String, displayName: String): Boolean {
        return TemporaryWriteIdentity(parentIdentity, displayName) in activeWrites
    }

    private fun targetNamePrefix(target: StorageTarget): String {
        val fingerprint = sha256(parentIdentity(target) + "\u0000" + targetName(target))
            .take(TEMPORARY_WRITE_TARGET_FINGERPRINT_LENGTH)
        return "${TEMPORARY_WRITE_NAME_PREFIX}${fingerprint}_"
    }

    private fun parentIdentity(target: StorageTarget): String = when (target) {
        is StorageTarget.FileTarget -> "file:${normalizedFileParent(target.logicalPath)}"
        is StorageTarget.SafTarget -> "saf:${target.parent.uri}"
    }

    private fun referenceIdentity(reference: StorageReference): String = when (reference) {
        is StorageReference.FileRef -> "file:${normalizedFilePath(reference.logicalPath)}"
        is StorageReference.SafRef -> "saf:${reference.uri}"
    }

    private fun targetName(target: StorageTarget): String {
        val ownerName = target.temporaryWriteOwnerName
        if (!ownerName.isNullOrEmpty()) {
            return when (target) {
                is StorageTarget.FileTarget -> normalizedFilePath(ownerName)
                is StorageTarget.SafTarget -> ownerName
            }
        }
        return when (target) {
            is StorageTarget.FileTarget -> normalizedFilePath(target.logicalPath)
            is StorageTarget.SafTarget -> target.displayName
        }
    }

    private fun normalizedFileParent(logicalPath: String): String {
        return normalizedFilePath(logicalPath)
            .substringBeforeLast('/', missingDelimiterValue = "")
    }

    private fun normalizedFilePath(logicalPath: String): String {
        return logicalPath
            .replace(File.separatorChar, '/')
            .split('/')
            .filter { segment -> segment.isNotEmpty() && segment != "." }
            .joinToString("/")
    }

    private fun randomNonce(): String {
        return UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(TEMPORARY_WRITE_NONCE_LENGTH)
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }

    private data class TemporaryWriteIdentity(
        val parentIdentity: String,
        val displayName: String
    )
}

internal data class ManagedTemporaryFile(
    val file: File,
    val lease: ManagedTemporaryWriteLease
)

/**
 * deletes only new, target-bound temporary writes after a terminal operation.
 * The listing must be complete, so a SAF provider failure cannot turn an
 * uncertain directory view into destructive cleanup.
 */
internal suspend fun StorageBackend.cleanupTerminalTemporaryWrites(
    target: StorageTarget
): ManagedTemporaryWriteCleanupResult = cleanupTerminalTemporaryWrites(listOf(target))

/**
 * clears several terminal targets from one parent listing to keep bulk cancellation bounded
 */
internal suspend fun StorageBackend.cleanupTerminalTemporaryWrites(
    targets: Collection<StorageTarget>
): ManagedTemporaryWriteCleanupResult {
    val uniqueTargets = targets.distinct()
    if (uniqueTargets.isEmpty()) {
        return ManagedTemporaryWriteCleanupResult.Completed(
            deletedCount = 0,
            missingCount = 0,
            retainedActiveCount = 0,
            failures = emptyList()
        )
    }
    val parent = ManagedTemporaryWriteArtifacts.parentReference(uniqueTargets.first())
    if (uniqueTargets.any { target ->
            ManagedTemporaryWriteArtifacts.parentReference(target) != parent
        }
    ) {
        return ManagedTemporaryWriteCleanupResult.Skipped(
            ManagedTemporaryWriteCleanupSkipReason.TargetParentMismatch
        )
    }
    val snapshot = list(parent)
    val plans = uniqueTargets.map { target ->
        ManagedTemporaryWriteArtifacts.planTerminalCleanup(
            parent = parent,
            target = target,
            snapshot = snapshot
        )
    }
    val skipReason = plans
        .mapNotNull(ManagedTemporaryWriteCleanupPlan::skipReason)
        .firstOrNull()
    if (skipReason != null) {
        return ManagedTemporaryWriteCleanupResult.Skipped(skipReason)
    }
    val candidates = plans
        .flatMap(ManagedTemporaryWriteCleanupPlan::candidates)
        .distinctBy(StorageStat::reference)
    var deletedCount = 0
    var missingCount = 0
    val failures = mutableListOf<StorageMutationResult>()
    for (candidate in candidates) {
        when (val result = delete(candidate.asTrustedManagedRef())) {
            StorageMutationResult.Deleted -> deletedCount += 1
            StorageMutationResult.Missing -> missingCount += 1
            StorageMutationResult.OutOfScope,
            StorageMutationResult.PermissionLost,
            is StorageMutationResult.ProviderFailure,
            is StorageMutationResult.Unsupported -> failures += result
        }
    }
    return ManagedTemporaryWriteCleanupResult.Completed(
        deletedCount = deletedCount,
        missingCount = missingCount,
        retainedActiveCount = plans.sumOf(ManagedTemporaryWriteCleanupPlan::retainedActiveCount),
        failures = failures
    )
}
