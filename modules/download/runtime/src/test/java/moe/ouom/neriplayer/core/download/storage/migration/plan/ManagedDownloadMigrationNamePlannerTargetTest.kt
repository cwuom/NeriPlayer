package moe.ouom.neriplayer.core.download.storage.migration.plan

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadMigrationNamePlannerTargetTest {

    @Test
    fun `target index resolves entries by trimmed reference media uri or local path`() {
        val audio = stored("Song.flac", "content://target/audio", localFilePath = "/music/Song.flac")
        val cover = stored("Song.jpg", "content://target/cover").copy(mediaUri = "content://media/cover")
        val lyric = stored("Song.lrc", "content://target/lyric").copy(mediaUri = "  ")
        val index = ManagedMigrationTargetIndex(
            rootEntriesByName = mapOf(audio.name to audio),
            coverEntriesByName = mapOf(cover.name to cover),
            lyricEntriesByName = mapOf(lyric.name to lyric)
        )

        assertEquals(audio, index.entryByReference(" content://target/audio "))
        assertEquals(audio, index.entryByReference("/music/Song.flac"))
        assertEquals(cover, index.entryByReference("content://media/cover"))
        assertEquals(lyric, index.entryByReference("content://target/lyric"))
        assertNull(index.entryByReference("content://target/missing"))
        assertNull(index.entryByReference("   "))
        assertNull(index.entryByReference(null))
    }

    @Test
    fun `target index looks up entries per subdirectory and rejects ambiguous names`() {
        val lyric = stored("Song.lrc", "content://target/lyric")
        val cover = stored("Song.jpg", "content://target/cover")
        val index = ManagedMigrationTargetIndex(
            rootEntriesByName = emptyMap(),
            coverEntriesByName = mapOf(cover.name to cover),
            lyricEntriesByName = mapOf(lyric.name to lyric),
            ambiguousNamesBySubdirectory = mapOf(LYRIC_SUBDIRECTORY to setOf("Taken.lrc"))
        )

        assertEquals(lyric, index.entryFor(LYRIC_SUBDIRECTORY, "Song.lrc"))
        assertEquals(cover, index.entryFor(COVER_SUBDIRECTORY, "Song.jpg"))
        assertNull(index.entryFor(LYRIC_SUBDIRECTORY, "Taken.lrc"))
        assertNull(index.entryFor("Other", "Song.lrc"))
        assertEquals(setOf("Song.lrc", "Taken.lrc"), index.namesFor(LYRIC_SUBDIRECTORY))
    }

    @Test
    fun `metadata lookup prefers the canonical sidecar over provider numbered copies`() {
        val audio = stored("Song.flac", "content://target/audio")
        val numbered = stored("Song.flac.npmeta (1).json", "content://target/meta-1")
        val canonical = stored("Song.flac.npmeta.json", "content://target/meta")
        val index = ManagedMigrationTargetIndex(
            rootEntriesByName = listOf(audio, numbered, canonical).associateBy { it.name },
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap()
        )

        assertEquals(canonical, index.metadataEntryForAudioName("Song.flac"))
        assertNull(index.metadataEntryForAudioName("Other.flac"))
    }

    @Test
    fun `unsafe persisted names are never restored`() {
        val source = coverSource("/source/cover.jpg")
        val generated = ManagedDownloadMigrationNamePlanner.buildNamePlan(listOf(source), emptyTargetIndex)

        listOf("   ", ".", "..", "nested/cover.jpg", "nested\\cover.jpg").forEach { unsafeName ->
            assertNull(
                unsafeName,
                ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                    entries = listOf(source),
                    targetIndex = emptyTargetIndex,
                    generatedPlan = generated,
                    persistedTargetNames = mapOf(source.entry.reference to unsafeName)
                )
            )
        }
        val restored = ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
            entries = listOf(source),
            targetIndex = emptyTargetIndex,
            generatedPlan = generated,
            persistedTargetNames = mapOf(source.entry.reference to "cover (7).jpg")
        )
        assertEquals("cover (7).jpg", restored?.targetNameFor(source))
    }

    @Test
    fun `persisted targets are reused unless both sizes are known and differ`() {
        val metadataSource = ManagedMigrationEntryRef(
            subdirectory = null,
            entry = stored("Song.flac.npmeta.json", "/source/Song.flac.npmeta.json", sizeBytes = 10L)
        )
        val unknownSourceSize = coverSource("/source/unknown.jpg", name = "unknown.jpg", sizeBytes = 0L)
        val unknownTargetSize = coverSource("/source/empty.jpg", name = "empty.jpg")
        val sameSize = coverSource("/source/same.jpg", name = "same.jpg")
        val metadataTarget = stored("Song.flac.npmeta.json", "content://target/meta", sizeBytes = 99L)
        val targets = listOf(
            stored("unknown.jpg", "content://target/unknown", sizeBytes = 99L),
            stored("empty.jpg", "content://target/empty", sizeBytes = 0L),
            stored("same.jpg", "content://target/same", sizeBytes = 42L)
        )
        val targetIndex = emptyTargetIndex.copy(
            rootEntriesByName = mapOf(metadataTarget.name to metadataTarget),
            coverEntriesByName = targets.associateBy { it.name }
        )
        val entries = listOf(metadataSource, unknownSourceSize, unknownTargetSize, sameSize)
        val generated = ManagedDownloadMigrationNamePlanner.buildNamePlan(entries, targetIndex)

        val restored = requireNotNull(
            ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                entries = entries,
                targetIndex = targetIndex,
                generatedPlan = generated,
                persistedTargetNames = entries.associate { it.entry.reference to it.entry.name }
            )
        )

        assertEquals(metadataTarget, restored.reusedTargetFor(metadataSource))
        assertEquals(targets[0], restored.reusedTargetFor(unknownSourceSize))
        assertEquals(targets[1], restored.reusedTargetFor(unknownTargetSize))
        assertEquals(targets[2], restored.reusedTargetFor(sameSize))
        entries.forEach { entry -> assertEquals(entry.entry.name, restored.targetNameFor(entry)) }
    }

    @Test
    fun `restored audio names carry their metadata sidecar along`() {
        val audio = ManagedMigrationEntryRef(null, stored("Song.flac", "/source/Song.flac"))
        val metadata = ManagedMigrationEntryRef(null, stored("Song.flac.npmeta.json", "/source/Song.flac.npmeta.json"))
        val other = ManagedMigrationEntryRef(null, stored("Other.flac", "/source/Other.flac"))
        val entries = listOf(audio, metadata, other)
        val generated = ManagedDownloadMigrationNamePlanner.buildNamePlan(entries, emptyTargetIndex)

        fun restore(persisted: Map<String, String>) = requireNotNull(
            ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(entries, emptyTargetIndex, generated, persisted)
        )

        val renamed = restore(mapOf(audio.entry.reference to "Song (3).flac"))
        assertEquals("Song (3).flac", renamed.targetNameFor(audio))
        assertEquals("Song (3).flac.npmeta.json", renamed.targetNameFor(metadata))
        assertEquals("Other.flac", renamed.targetNameFor(other))

        val unchanged = restore(mapOf(audio.entry.reference to "Song.flac"))
        assertEquals("Song.flac.npmeta.json", unchanged.targetNameFor(metadata))

        val bothPersisted = restore(
            mapOf(audio.entry.reference to "Song (3).flac", metadata.entry.reference to "legacy.npmeta.json")
        )
        assertEquals("legacy.npmeta.json", bothPersisted.targetNameFor(metadata))
    }

    @Test
    fun `identity replacement reuses target sidecars referenced by target metadata`() {
        val sourceAudio = ManagedMigrationEntryRef(null, stored("Song.flac", "/source/Song.flac"))
        val sourceCover = coverSource("/source/Covers/Song.jpg", name = "Song.jpg")
        val targetAudio = stored("Remote.flac", "content://target/audio")
        val targetCover = stored("Remote.jpg", "content://target/cover")
        val targetIndex = emptyTargetIndex.copy(
            rootEntriesByName = mapOf(targetAudio.name to targetAudio),
            coverEntriesByName = mapOf(targetCover.name to targetCover),
            metadataByAudioName = mapOf(
                targetAudio.name to DownloadedAudioMetadata(stableKey = "song-1", coverPath = targetCover.reference)
            )
        )
        val entries = listOf(sourceAudio, sourceCover)

        fun plan(sourceCoverPath: String, targetCoverName: String = "Song.jpg"): ManagedMigrationNamePlan {
            val renamedTargetCover = targetCover.copy(name = targetCoverName)
            return ManagedDownloadMigrationNamePlanner.buildNamePlan(
                entries = entries,
                targetIndex = targetIndex.copy(coverEntriesByName = mapOf(targetCoverName to renamedTargetCover)),
                sourceMetadataByAudioName = mapOf(
                    sourceAudio.entry.name to DownloadedAudioMetadata(stableKey = "song-1", coverPath = sourceCoverPath)
                )
            )
        }

        val exactName = plan(sourceCover.entry.reference)
        assertEquals("Remote.flac", exactName.targetNameFor(sourceAudio))
        assertEquals("Song.jpg", exactName.targetNameFor(sourceCover))
        assertEquals(
            exactName.replacementFor(sourceAudio)?.groupIdentity,
            exactName.replacementFor(sourceCover)?.groupIdentity
        )

        val sameStem = plan(sourceCover.entry.name, targetCoverName = "Song.png")
        assertEquals("Song.png", sameStem.targetNameFor(sourceCover))
        assertEquals(COVER_SUBDIRECTORY, sameStem.replacementFor(sourceCover)?.subdirectory)

        val unrelated = plan(sourceCover.entry.reference, targetCoverName = "Remote.jpg")
        assertEquals("Song.jpg", unrelated.targetNameFor(sourceCover))
        assertNull(unrelated.replacementFor(sourceCover))
        assertNotNull(unrelated.replacementFor(sourceAudio))
    }

    @Test
    fun `conflicting or ambiguous identities do not select a replacement target`() {
        val source = ManagedMigrationEntryRef(null, stored("Song.flac", "/source/Song.flac"))
        val first = stored("First.flac", "content://target/first")
        val second = stored("Second.flac", "content://target/second")

        fun plan(
            targetMetadata: Map<String, DownloadedAudioMetadata>,
            sourceMetadata: DownloadedAudioMetadata
        ) = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(source),
            targetIndex = emptyTargetIndex.copy(
                rootEntriesByName = mapOf(first.name to first, second.name to second),
                metadataByAudioName = targetMetadata
            ),
            sourceMetadataByAudioName = mapOf(source.entry.name to sourceMetadata)
        )

        val matched = plan(
            mapOf(first.name to DownloadedAudioMetadata(stableKey = "song-1")),
            DownloadedAudioMetadata(stableKey = "song-1")
        )
        assertEquals("First.flac", matched.targetNameFor(source))
        val matchedDespiteUnknownOperation = plan(
            mapOf(first.name to DownloadedAudioMetadata(stableKey = "song-1")),
            DownloadedAudioMetadata(stableKey = "song-1", operationId = "op-unknown")
        )
        assertEquals("First.flac", matchedDespiteUnknownOperation.targetNameFor(source))
        assertEquals("stableKey:song-1", matchedDespiteUnknownOperation.replacementFor(source)?.groupIdentity)

        val conflicting = plan(
            mapOf(
                first.name to DownloadedAudioMetadata(stableKey = "song-1"),
                second.name to DownloadedAudioMetadata(operationId = "op-1")
            ),
            DownloadedAudioMetadata(stableKey = "song-1", operationId = "op-1")
        )
        val ambiguous = plan(
            mapOf(
                first.name to DownloadedAudioMetadata(stableKey = "song-1"),
                second.name to DownloadedAudioMetadata(stableKey = "song-1")
            ),
            DownloadedAudioMetadata(stableKey = "song-1")
        )
        val anonymous = plan(
            mapOf(first.name to DownloadedAudioMetadata(stableKey = "song-1")),
            DownloadedAudioMetadata(name = "Song")
        )
        listOf(conflicting, ambiguous, anonymous).forEach { result ->
            assertEquals("Song.flac", result.targetNameFor(source))
            assertNull(result.replacementFor(source))
        }
    }

    private fun coverSource(
        reference: String,
        name: String = "cover.jpg",
        sizeBytes: Long = 42L
    ): ManagedMigrationEntryRef {
        return ManagedMigrationEntryRef(
            subdirectory = COVER_SUBDIRECTORY,
            entry = stored(name = name, reference = reference, sizeBytes = sizeBytes)
        )
    }

    private fun stored(
        name: String,
        reference: String,
        sizeBytes: Long = 42L,
        localFilePath: String? = reference.takeIf { it.startsWith('/') }
    ) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = reference,
        mediaUri = reference,
        localFilePath = localFilePath,
        sizeBytes = sizeBytes,
        lastModifiedMs = 1L
    )

    private companion object {
        val emptyTargetIndex = ManagedMigrationTargetIndex(
            rootEntriesByName = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap()
        )
    }
}
