package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class PlaybackDatasetLeaseSymlinkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun closingOwnedDatasetUnlinksChildrenWithoutDeletingForeignTargets() = runBlocking {
        val directory = temporary.newFolder()
        val foreign = temporary.newFolder()
        val keep = File(foreign, "nested/keep.bin").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("foreign content")
        }
        val dataset = FileSyncPlaybackDatasetStore(directory).fromLegacy(
            SyncData(playbackStats = listOf(SyncTrackStat(identityKey = "owned")))
        )
        val session = directory.listFiles().orEmpty().single()
        val link = File(session, "foreign-directory").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        try {
            dataset.close()
            assertEquals("foreign content", keep.readText())
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
            assertFalse(session.exists())
        } finally {
            Files.deleteIfExists(link)
            dataset.close()
        }
    }

    @Test fun abandonedRecoveryRefusesSessionDirectoryLinks() {
        val directory = temporary.newFolder()
        val foreign = temporary.newFolder()
        val owner = File(foreign, "owner.lock").apply { writeText(MARKER) }
        val keep = File(foreign, "keep.bin").apply { writeText("foreign content") }
        val link = File(directory, "dataset-linked").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        try {
            PlaybackDatasetLease.recover(link.toFile())
            FileSyncPlaybackDatasetStore(directory)
            assertEquals("foreign content", keep.readText())
            assertEquals(MARKER, owner.readText())
            assertTrue(Files.isSymbolicLink(link))
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test fun abandonedRecoveryRefusesLinkedOwnershipMarkers() {
        val directory = temporary.newFolder()
        val foreign = temporary.newFile().apply { writeText(MARKER) }
        val session = File(directory, "dataset-linked-owner").apply { mkdir() }
        val keep = File(session, "keep.bin").apply { writeText("unproven ownership") }
        val link = File(session, "owner.lock").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        try {
            FileSyncPlaybackDatasetStore(directory)
            assertEquals(MARKER, foreign.readText())
            assertEquals("unproven ownership", keep.readText())
            assertTrue(Files.isSymbolicLink(link))
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test fun linkedStoreRootIsRejectedWithoutRecoveringForeignSessions() {
        val foreign = temporary.newFolder()
        val session = File(foreign, "dataset-abandoned").apply { mkdir() }
        val owner = File(session, "owner.lock").apply { writeText(MARKER) }
        val keep = File(session, "keep.bin").apply { writeText("foreign content") }
        val link = File(temporary.root, "store-linked").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        try {
            assertTrue(runCatching { FileSyncPlaybackDatasetStore(link.toFile()) }.isFailure)
            assertEquals("foreign content", keep.readText())
            assertEquals(MARKER, owner.readText())
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test fun leaseCreationRefusesLinkedDirectories() {
        val foreign = temporary.newFolder()
        val keep = File(foreign, "keep.bin").apply { writeText("foreign content") }
        val link = File(temporary.root, "session-linked").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        var lease: PlaybackDatasetLease? = null
        try {
            val failure = runCatching { PlaybackDatasetLease.create(link.toFile()).also { lease = it } }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("foreign content", keep.readText())
            assertFalse(File(foreign, "owner.lock").exists())
        } finally {
            lease?.closeAndDelete()
            Files.deleteIfExists(link)
        }
    }

    @Test fun leaseCreationRefusesLinkedMarkersWithoutChangingTheirTargets() {
        val session = temporary.newFolder()
        val foreign = temporary.newFile().apply { writeText("foreign ownership marker") }
        val link = File(session, "owner.lock").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        var lease: PlaybackDatasetLease? = null
        try {
            val failure = runCatching { PlaybackDatasetLease.create(session).also { lease = it } }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("foreign ownership marker", foreign.readText())
            assertTrue(Files.isSymbolicLink(link))
        } finally {
            lease?.closeAndDelete()
            Files.deleteIfExists(link)
        }
    }

    @Test fun danglingChildLinksAreRemovedAsOwnedDirectoryEntries() {
        val session = temporary.newFolder()
        val lease = PlaybackDatasetLease.create(session)
        val link = File(session, "dangling").toPath()
        Files.createSymbolicLink(link, File(temporary.root, "absent-target").toPath())
        try {
            lease.closeAndDelete()
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
            assertFalse(session.exists())
        } finally {
            Files.deleteIfExists(link)
        }
    }

    private companion object {
        const val MARKER = "NERI_SYNC_PLAYBACK_STAGE_1"
    }
}
