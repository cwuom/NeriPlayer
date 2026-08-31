package moe.ouom.neriplayer.core.download.storage.migration

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationSourceAuthorityTest {

    @Test
    fun `existing target and backup are rewritten from source and backup is retained`() {
        val directory = Files.createTempDirectory("migration-source-authority").toFile()
        val target = File(directory, "track.mp3").apply { writeText("old-target") }
        val backup = File(directory, ".np-migration-backup-track.mp3").apply {
            writeText("old-backup")
        }
        try {
            val replacement = ManagedDownloadCommitIo.copyFileReplacementAtomically(
                parent = directory,
                targetName = target.name,
                backupName = backup.name,
                input = ByteArrayInputStream("source".toByteArray()),
                bufferSizeBytes = 4
            )

            assertEquals("source", target.readText())
            assertEquals("old-backup", backup.readText())
            assertEquals(6L, replacement.copiedBytes)
            assertEquals(backup, replacement.backup)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed source rewrite leaves target and backup for a later retry`() {
        val directory = Files.createTempDirectory("migration-source-retry").toFile()
        val target = File(directory, "track.mp3").apply { writeText("old-target") }
        val backup = File(directory, ".np-migration-backup-track.mp3").apply {
            writeText("old-backup")
        }
        try {
            val failingInput = object : ByteArrayInputStream("partial".toByteArray()) {
                private var failed = false

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (failed) throw IOException("injected source failure")
                    failed = true
                    return super.read(buffer, offset, length)
                }
            }
            assertTrue(
                runCatching {
                    ManagedDownloadCommitIo.copyFileReplacementAtomically(
                        parent = directory,
                        targetName = target.name,
                        backupName = backup.name,
                        input = failingInput,
                        bufferSizeBytes = 4
                    )
                }.isFailure
            )
            assertEquals("old-target", target.readText())
            assertEquals("old-backup", backup.readText())

            ManagedDownloadCommitIo.copyFileReplacementAtomically(
                parent = directory,
                targetName = target.name,
                backupName = backup.name,
                input = ByteArrayInputStream("source".toByteArray()),
                bufferSizeBytes = 4
            )
            assertEquals("source", target.readText())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".pending") })
            assertEquals("old-backup", backup.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `weak metadata identity never replaces a different target name`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 10L)
        val targetAudio = entry("other.mp3", "/target/other.mp3", 10L)
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
        assertEquals(sourceAudio.name, plan.targetNameFor(sourceRef))
        assertNull(plan.replacementFor(sourceRef))
    }

    @Test
    fun `managed same name conflict gives source an atomic replacement`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 20L)
        val targetAudio = entry("track.mp3", "/target/track.mp3", 10L)
        val plan = plan(
            sourceAudio = sourceAudio,
            sourceMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "source-key"
            ),
            targetAudio = targetAudio,
            targetMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "target-key"
            )
        )

        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val replacement = plan.replacementFor(sourceRef)

        assertEquals(targetAudio.name, plan.targetNameFor(sourceRef))
        assertEquals(targetAudio.name, replacement?.targetName)
        assertTrue(replacement?.groupIdentity?.startsWith("managedName:") == true)
    }

    @Test
    fun `same name target without managed metadata is replaced by the source`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 20L)
        val targetAudio = entry("track.mp3", "/target/track.mp3", 10L)
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetAudio.name to targetAudio),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap()
            ),
            sourceMetadataByAudioName = mapOf(
                sourceAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = "source-key"
                )
            )
        )

        assertEquals(targetAudio.name, plan.targetNameFor(sourceRef))
        assertEquals(targetAudio.name, plan.replacementFor(sourceRef)?.targetName)
        assertTrue(plan.replacementFor(sourceRef)?.groupIdentity?.startsWith("name:") == true)
    }

    @Test
    fun `same name metadata target is replaced even when metadata cannot be parsed`() {
        val sourceMetadata = entry("track.mp3.npmeta.json", "/source/track.mp3.npmeta.json", 20L)
        val targetMetadata = entry("track.mp3.npmeta.json", "/target/track.mp3.npmeta.json", 10L)
        val sourceRef = ManagedMigrationEntryRef(null, sourceMetadata)
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetMetadata.name to targetMetadata),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap()
            )
        )

        assertEquals(targetMetadata.name, plan.targetNameFor(sourceRef))
        assertEquals(targetMetadata.name, plan.replacementFor(sourceRef)?.targetName)
    }

    @Test
    fun `ambiguous case insensitive managed targets are not overwritten`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 20L)
        val targetAudio = entry("Track.MP3", "/target/Track.MP3", 10L)
        val secondTarget = entry("TRACK.MP3", "/target/TRACK.MP3", 11L)
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(
                    targetAudio.name to targetAudio,
                    secondTarget.name to secondTarget
                ),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                metadataByAudioName = mapOf(
                    targetAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "target-a"
                    ),
                    secondTarget.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "target-b"
                    )
                )
            ),
            sourceMetadataByAudioName = mapOf(
                sourceAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = "source-key"
                )
            )
        )

        assertEquals("track (1).mp3", plan.targetNameFor(sourceRef))
        assertNull(plan.replacementFor(sourceRef))
    }

    @Test
    fun `same name directory is never treated as a replacement target`() {
        val sourceAudio = entry("track.mp3", "/source/track.mp3", 20L)
        val targetDirectory = entry("track.mp3", "/target/track.mp3", 0L).copy(
            isDirectory = true
        )
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetDirectory.name to targetDirectory),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                metadataByAudioName = mapOf(
                    targetDirectory.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "target-key"
                    )
                )
            ),
            sourceMetadataByAudioName = mapOf(
                sourceAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = "source-key"
                )
            )
        )

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
            targetAudio = targetAudio.copy(name = "other.mp3"),
            targetMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "target-key"
            )
        )
        assertEquals(sourceAudio.name, differentIdentityPlan.targetNameFor(sourceRef))
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
