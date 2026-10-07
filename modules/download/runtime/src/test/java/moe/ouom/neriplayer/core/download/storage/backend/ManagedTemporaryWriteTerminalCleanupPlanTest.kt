package moe.ouom.neriplayer.core.download.storage.backend

import moe.ouom.neriplayer.data.model.download.storage.StorageConfidence
import moe.ouom.neriplayer.data.model.download.storage.StorageDirectorySnapshot
import moe.ouom.neriplayer.data.model.download.storage.StorageReference
import moe.ouom.neriplayer.data.model.download.storage.StorageStat
import moe.ouom.neriplayer.data.model.download.storage.StorageTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedTemporaryWriteTerminalCleanupPlanTest {
    private val parent = StorageReference.FileRef("/music/NeriPlayer")
    private val target = StorageTarget.FileTarget("/music/NeriPlayer/Song.flac")
    private val sibling = StorageTarget.FileTarget("/music/NeriPlayer/Other.flac")
    private val leases = mutableListOf<ManagedTemporaryWriteLease>()

    @After
    fun releaseLeases() {
        leases.forEach(ManagedTemporaryWriteLease::close)
    }

    @Test
    fun `cleanup without targets plans nothing`() {
        val stale = file(ManagedTemporaryWriteArtifacts.displayNameFor(target, "0123456789abcdef"))

        val plan = ManagedTemporaryWriteArtifacts.planTerminalCleanup(
            parent = parent,
            targets = emptyList(),
            snapshot = StorageDirectorySnapshot(listOf(stale), StorageConfidence.Complete)
        )

        assertEquals(ManagedTemporaryWriteCleanupPlan(emptyList(), retainedActiveCount = 0), plan)
    }

    @Test
    fun `targets outside the listed parent and incomplete listings are skipped`() {
        val stale = file(ManagedTemporaryWriteArtifacts.displayNameFor(target, "0123456789abcdef"))

        val mismatch = ManagedTemporaryWriteArtifacts.planTerminalCleanup(
            parent = StorageReference.FileRef("/music/Other"),
            target = target,
            snapshot = StorageDirectorySnapshot(listOf(stale), StorageConfidence.Complete)
        )
        val incomplete = ManagedTemporaryWriteArtifacts.planTerminalCleanup(
            parent = parent,
            target = target,
            snapshot = StorageDirectorySnapshot(listOf(stale), StorageConfidence.PermissionLost)
        )

        assertEquals(
            ManagedTemporaryWriteCleanupPlan(
                candidates = emptyList(),
                retainedActiveCount = 0,
                skipReason = ManagedTemporaryWriteCleanupSkipReason.TargetParentMismatch
            ),
            mismatch
        )
        assertEquals(
            ManagedTemporaryWriteCleanupPlan(
                candidates = emptyList(),
                retainedActiveCount = 0,
                skipReason = ManagedTemporaryWriteCleanupSkipReason.IncompleteDirectory(
                    StorageConfidence.PermissionLost
                )
            ),
            incomplete
        )
    }

    @Test
    fun `only inactive temporary files of the target become cleanup candidates`() {
        val stale = file(ManagedTemporaryWriteArtifacts.displayNameFor(target, "0123456789abcdef"))
        val activeLease = checkNotNull(
            ManagedTemporaryWriteArtifacts.acquire(target, "fedcba9876543210")
        ).also(leases::add)
        val active = file(activeLease.displayName)
        val siblingTemporary = file(
            ManagedTemporaryWriteArtifacts.displayNameFor(sibling, "00112233445566aa")
        )
        val directory = stat(
            ManagedTemporaryWriteArtifacts.displayNameFor(target, "aabbccddeeff0011"),
            isDirectory = true
        )
        val audio = file("Song.flac")

        val plan = ManagedTemporaryWriteArtifacts.planTerminalCleanup(
            parent = parent,
            targets = listOf(target, target),
            snapshot = StorageDirectorySnapshot(
                entries = listOf(stale, active, siblingTemporary, directory, audio),
                confidence = StorageConfidence.Complete
            )
        )

        assertEquals(listOf(stale), plan.candidates)
        assertEquals(1, plan.retainedActiveCount)
        assertNull(plan.skipReason)
    }

    private fun file(name: String) = stat(name, isDirectory = false)

    private fun stat(name: String, isDirectory: Boolean) = StorageStat(
        reference = StorageReference.FileRef("/music/NeriPlayer/$name"),
        displayName = name,
        sizeBytes = if (isDirectory) null else 16L,
        lastModifiedMs = 1L,
        isDirectory = isDirectory
    )
}
