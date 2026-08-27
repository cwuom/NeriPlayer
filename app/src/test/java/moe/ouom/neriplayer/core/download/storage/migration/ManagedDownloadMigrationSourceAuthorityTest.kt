package moe.ouom.neriplayer.core.download.storage.migration

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationSourceAuthorityTest {

    @Test
    fun `weak metadata identity never schedules replacement`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 10L)
        val targetAudio = entry("track.mp3", "/target/track.mp3", 10L)
        val plan = plan(
            sourceAudio = sourceAudio,
            sourceMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                songId = 42L,
                identityAlbum = "album",
                mediaUri = "content://source/audio"
            ),
            targetAudio = targetAudio,
            targetMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                songId = 42L,
                identityAlbum = "album",
                mediaUri = "content://target/audio"
            )
        )

        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        assertEquals("track (1).mp3", plan.targetNameFor(sourceRef))
        assertNull(plan.replacementFor(sourceRef))
    }

    @Test
    fun `same stable key schedules deterministic source authoritative replacement`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 20L)
        val targetAudio = entry("track.mp3", "/target/track.mp3", 10L)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(stableKey = "song-42")
        val plan = plan(
            sourceAudio = sourceAudio,
            sourceMetadata = metadata,
            targetAudio = targetAudio,
            targetMetadata = metadata.copy(mediaUri = "content://target/audio")
        )
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val replacement = plan.replacementFor(sourceRef)

        assertEquals(targetAudio.name, plan.targetNameFor(sourceRef))
        assertTrue(replacement?.backupName?.startsWith(".np-migration-backup-") == true)
        assertEquals(
            replacement?.backupName,
            ManagedDownloadMigrationNamePlanner.buildNamePlan(
                entries = listOf(sourceRef),
                targetIndex = ManagedMigrationTargetIndex(
                    rootEntriesByName = mapOf(targetAudio.name to targetAudio),
                    coverEntriesByName = emptyMap(),
                    lyricEntriesByName = emptyMap(),
                    metadataByAudioName = mapOf(targetAudio.name to metadata.copy(mediaUri = "content://target/audio"))
                ),
                sourceMetadataByAudioName = mapOf(sourceAudio.name to metadata)
            ).replacementFor(sourceRef)?.backupName
        )
    }

    @Test
    fun `same operation id is strong identity while same bytes with different key stays separate`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 12L)
        val targetAudio = entry("track.mp3", "/target/track.mp3", 12L)
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val operationPlan = plan(
            sourceAudio = sourceAudio,
            sourceMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                operationId = "operation-7"
            ),
            targetAudio = targetAudio,
            targetMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                operationId = "operation-7"
            )
        )
        assertEquals(targetAudio.name, operationPlan.targetNameFor(sourceRef))
        assertTrue(operationPlan.replacementFor(sourceRef) != null)

        val differentIdentityPlan = plan(
            sourceAudio = sourceAudio,
            sourceMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "source-key"
            ),
            targetAudio = targetAudio,
            targetMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "target-key"
            )
        )
        assertNotEquals(targetAudio.name, differentIdentityPlan.targetNameFor(sourceRef))
        assertNull(differentIdentityPlan.replacementFor(sourceRef))
    }

    private fun plan(
        sourceAudio: ManagedDownloadStorage.StoredEntry,
        sourceMetadata: ManagedDownloadStorage.DownloadedAudioMetadata,
        targetAudio: ManagedDownloadStorage.StoredEntry,
        targetMetadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): ManagedMigrationNamePlan {
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        return ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetAudio.name to targetAudio),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                metadataByAudioName = mapOf(targetAudio.name to targetMetadata)
            ),
            sourceMetadataByAudioName = mapOf(sourceAudio.name to sourceMetadata)
        )
    }

    private fun entry(name: String, reference: String, sizeBytes: Long): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = File(reference).toURI().toString(),
            localFilePath = reference,
            sizeBytes = sizeBytes,
            lastModifiedMs = 1L
        )
    }
}
