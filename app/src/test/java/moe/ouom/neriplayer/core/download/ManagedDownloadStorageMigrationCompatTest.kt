package moe.ouom.neriplayer.core.download

import android.content.Context
import java.text.Normalizer
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetResolver
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntryRef
import moe.ouom.neriplayer.core.download.storage.migration.InputStreamManagedMigrationEntryReader
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.MIGRATION_PROGRESS_EMIT_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteArtifacts
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis


class ManagedDownloadStorageMigrationCompatTest : ManagedDownloadStorageMigrationCompatTestSupport() {

    @Test
    fun `migration pending artifact detector accepts legacy root and tmp names`() {
        fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "/downloads/$name",
            mediaUri = "file:///downloads/$name",
            localFilePath = "/downloads/$name",
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )

        val legacyPendingAudio = entry("song.mp3.npdl_pending.legacy.pending")
        val temporaryPendingMetadata = entry("song.mp3.npmeta.pending.json")
        val formalMetadata = entry("song.mp3.npmeta.json")
        val names = ManagedDownloadMigrationEntryCollector.pendingArtifactNames(
            rootEntries = listOf(legacyPendingAudio, formalMetadata),
            temporaryEntries = listOf(temporaryPendingMetadata)
        )

        assertEquals(
            listOf(legacyPendingAudio.name, temporaryPendingMetadata.name),
            names
        )
        assertTrue(
            ManagedDownloadMigrationEntryCollector.hasPendingArtifacts(
                rootEntries = listOf(legacyPendingAudio),
                temporaryEntries = emptyList()
            )
        )
        assertFalse(
            ManagedDownloadMigrationEntryCollector.hasPendingArtifacts(
                rootEntries = listOf(formalMetadata),
                temporaryEntries = emptyList()
            )
        )
    }

    @Test
    fun `migration pending artifact detector ignores ordinary names containing marker`() {
        val ordinary = ManagedDownloadStorage.StoredEntry(
            name = "marker.npdl_pending.mp3",
            reference = "/downloads/marker.npdl_pending.mp3",
            mediaUri = "file:///downloads/marker.npdl_pending.mp3",
            localFilePath = "/downloads/marker.npdl_pending.mp3",
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )

        assertFalse(ManagedDownloadMigrationEntryCollector.isPendingArtifact(ordinary))
    }

    @Test
    fun `migration blocks a pending pair but preserves metadata only evidence`() {
        fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "/downloads/$name",
            mediaUri = "file:///downloads/$name",
            localFilePath = "/downloads/$name",
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )

        val pendingAudio = entry("paired.mp3.npdl_pending.operation.pending")
        val pairedMetadata = entry("paired.mp3.npmeta.pending.json")
        val metadataOnly = entry("orphan.mp3.npmeta.pending.json")

        val classification = ManagedDownloadMigrationEntryCollector.classifyPendingArtifacts(
            rootEntries = listOf(pairedMetadata, metadataOnly),
            temporaryEntries = listOf(pendingAudio)
        )

        assertEquals(
            listOf(pairedMetadata.name, pendingAudio.name).sorted(),
            classification.blockingNames
        )
        assertEquals(listOf(metadataOnly.name), classification.metadataOnlyNames)
        assertTrue(
            ManagedDownloadMigrationEntryCollector.hasBlockingPendingArtifacts(
                rootEntries = listOf(pairedMetadata, metadataOnly),
                temporaryEntries = listOf(pendingAudio)
            )
        )
        assertFalse(
            ManagedDownloadMigrationEntryCollector.hasBlockingPendingArtifacts(
                rootEntries = listOf(metadataOnly),
                temporaryEntries = emptyList()
            )
        )
    }

    @Test
    fun `metadata naming recognizes provider numbering before the json extension`() {
        val audioName = "言って。 - Neri - 言って。 - netease.mp3"
        val canonicalName = "$audioName.npmeta.json"
        val numberedBeforeExtension = "$audioName.npmeta (1).json"
        val numberedAfterExtension = "$canonicalName (2)"

        assertEquals(audioName, ManagedDownloadTreeNaming.metadataAudioName(canonicalName))
        assertEquals(audioName, ManagedDownloadTreeNaming.metadataAudioName(numberedBeforeExtension))
        assertEquals(audioName, ManagedDownloadTreeNaming.metadataAudioName(numberedAfterExtension))
        assertEquals(0, ManagedDownloadTreeNaming.metadataNameOrdinal(canonicalName, audioName))
        assertEquals(1, ManagedDownloadTreeNaming.metadataNameOrdinal(numberedBeforeExtension, audioName))
        assertEquals(2, ManagedDownloadTreeNaming.metadataNameOrdinal(numberedAfterExtension, audioName))
    }

    @Test
    fun `pending audio names end with a non audio sentinel and expose logical name`() {
        val names = ManagedDownloadPendingAudioWriteNames()
        val finalName = "Artist - Song.mp3"
        val pendingName = names.buildPendingAudioWriteName(finalName)
        val entry = ManagedDownloadStorage.StoredEntry(
            name = pendingName,
            reference = "/downloads/$pendingName",
            mediaUri = "file:///downloads/$pendingName",
            localFilePath = "/downloads/$pendingName",
            sizeBytes = 12L,
            lastModifiedMs = 1L
        )

        assertTrue(names.isPendingAudioWriteName(pendingName))
        assertFalse(pendingName.endsWith(".mp3", ignoreCase = true))
        assertTrue(entry.isPendingAudioWrite)
        assertEquals(finalName, entry.logicalName)
        assertEquals("Song", entry.nameWithoutExtension.substringAfter(" - "))
        assertEquals("", entry.extension)
        assertEquals("", entry.playbackUri)
    }

    @Test
    fun `pending audio names remain unique after a process restart`() {
        val finalName = "Artist - Song.mp3"
        val beforeRestart = ManagedDownloadPendingAudioWriteNames()
            .buildPendingAudioWriteName(finalName)
        val afterRestart = ManagedDownloadPendingAudioWriteNames()
            .buildPendingAudioWriteName(finalName)

        assertNotEquals(beforeRestart, afterRestart)
        assertTrue(beforeRestart.endsWith(".pending"))
        assertTrue(afterRestart.endsWith(".pending"))
    }

    @Test
    fun `song title containing pending marker is not treated as an artifact`() {
        val names = ManagedDownloadPendingAudioWriteNames()
        val title = "marker${moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER}.mp3"

        assertFalse(names.isPendingAudioWriteName(title))
        assertEquals(title, names.logicalAudioName(title))
        assertFalse(
            ManagedDownloadStorage.StoredEntry(
                name = title,
                reference = "/downloads/$title",
                mediaUri = "file:///downloads/$title",
                localFilePath = "/downloads/$title",
                sizeBytes = 1L,
                lastModifiedMs = 1L
            ).isPendingAudioWrite
        )
    }

    @Test
    fun `bounded audio names leave room for pending write recovery`() {
        val finalName = boundManagedDownloadFileName(
            "今、歩き出す君へ。 - Ceui - PCゲーム「いますぐお兄ちゃんに妹だっていいたい!」" +
                "ボーカルアルバム - netease - 😀😀😀😀😀😀😀😀.mp3"
        )
        val names = ManagedDownloadPendingAudioWriteNames()
        val pendingName = names.buildPendingAudioWriteName(finalName)

        assertEquals(finalName, names.logicalAudioName(pendingName))
        assertTrue(pendingName.toByteArray(Charsets.UTF_8).size <= 192)
        assertTrue(names.isPendingAudioWriteName(pendingName))
    }

    @Test
    fun `pending metadata cleanup recognizes provider numbered variants only`() {
        val audioName = "Artist - Song.mp3"
        val canonicalPending = "$audioName.npmeta.pending.json"
        val numberedBeforeExtension = "$audioName.npmeta.pending (1).json"
        val numberedAfterExtension = "$canonicalPending (2)"
        val committedMetadata = "$audioName.npmeta.json"

        assertTrue(ManagedDownloadTreeNaming.isPendingMetadataName(canonicalPending, audioName))
        assertEquals(audioName, ManagedDownloadTreeNaming.metadataAudioName(canonicalPending))
        assertTrue(
            ManagedDownloadTreeNaming.isPendingMetadataName(
                numberedBeforeExtension,
                audioName
            )
        )
        assertEquals(
            audioName,
            ManagedDownloadTreeNaming.metadataAudioName(numberedBeforeExtension)
        )
        assertTrue(
            ManagedDownloadTreeNaming.isPendingMetadataName(
                numberedAfterExtension,
                audioName
            )
        )
        assertEquals(
            audioName,
            ManagedDownloadTreeNaming.metadataAudioName(numberedAfterExtension)
        )
        assertFalse(ManagedDownloadTreeNaming.isPendingMetadataName(committedMetadata, audioName))
        assertEquals(
            listOf(canonicalPending, numberedAfterExtension, numberedBeforeExtension).sorted(),
            ManagedDownloadStorage.pendingMetadataEntryNames(
                audioName = audioName,
                candidateNames = listOf(
                    committedMetadata,
                    numberedBeforeExtension,
                    canonicalPending,
                    numberedAfterExtension
                )
            )
        )
    }

    @Test
    fun `snapshot indexes numbered metadata before json extension by audio name`() {
        val audioName = "言って。 - Neri - 言って。 - netease.mp3"
        val metadata = ManagedDownloadStorage.StoredEntry(
            name = "$audioName.npmeta (1).json",
            reference = "/downloads/$audioName.npmeta (1).json",
            mediaUri = "file:///downloads/$audioName.npmeta%20(1).json",
            localFilePath = "/downloads/$audioName.npmeta (1).json",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )

        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = emptyList(),
            metadataEntries = listOf(metadata),
            metadataByAudioName = emptyMap(),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )

        assertEquals(metadata, snapshot.metadataEntriesByAudioName[audioName])
    }

    @Test
    fun `migration plan reuses numbered metadata residue`() {
        val audioName = "言って。 - Neri - 言って。 - netease.mp3"
        val source = ManagedDownloadStorage.StoredEntry(
            name = "$audioName.npmeta.json",
            reference = "/source/$audioName.npmeta.json",
            mediaUri = "file:///source/$audioName.npmeta.json",
            localFilePath = "/source/$audioName.npmeta.json",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        val existing = source.copy(
            name = "$audioName.npmeta (2).json",
            reference = "content://target/numbered-metadata",
            mediaUri = "content://target/numbered-metadata",
            localFilePath = null
        )
        val targetIndex = ManagedMigrationTargetIndex(
            rootEntriesByName = mapOf(existing.name to existing),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap()
        )

        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(ManagedMigrationEntryRef(subdirectory = null, entry = source)),
            targetIndex = targetIndex
        )

        assertEquals(existing.name, plan.targetNameFor(ManagedMigrationEntryRef(null, source)))
        assertEquals(existing, plan.reusedTargetFor(ManagedMigrationEntryRef(null, source)))
    }

    @Test
    fun `migration plan reserves deterministic names for colliding sidecars`() {
        fun cover(reference: String) = ManagedDownloadStorage.StoredEntry(
            name = "cover.jpg",
            reference = reference,
            mediaUri = reference,
            localFilePath = reference,
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        val first = ManagedMigrationEntryRef(COVER_SUBDIRECTORY, cover("/source-a/cover.jpg"))
        val second = ManagedMigrationEntryRef(COVER_SUBDIRECTORY, cover("/source-b/cover.jpg"))

        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(second, first),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = emptyMap(),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap()
            )
        )

        assertEquals("cover.jpg", plan.targetNameFor(first))
        assertNotEquals(plan.targetNameFor(first), plan.targetNameFor(second))
        assertEquals(
            setOf("cover.jpg", "cover (1).jpg"),
            setOf(plan.targetNameFor(first), plan.targetNameFor(second))
        )
    }

    @Test
    fun `migration plan treats case variants as the same SAF target name`() {
        val source = ManagedDownloadStorage.StoredEntry(
            name = "cover.jpg",
            reference = "/source/cover.jpg",
            mediaUri = "file:///source/cover.jpg",
            localFilePath = "/source/cover.jpg",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        val existing = source.copy(
            name = "Cover.JPG",
            reference = "content://target/cover",
            mediaUri = "content://target/cover",
            localFilePath = null
        )
        val sourceRef = ManagedMigrationEntryRef(COVER_SUBDIRECTORY, source)

        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = emptyMap(),
                coverEntriesByName = mapOf(existing.name to existing),
                lyricEntriesByName = emptyMap()
            )
        )

        assertEquals("cover (1).jpg", plan.targetNameFor(sourceRef))
    }

    @Test
    fun `persisted migration name restores the exact target as a hash candidate`() {
        val source = ManagedDownloadStorage.StoredEntry(
            name = "cover.jpg",
            reference = "/source/cover.jpg",
            mediaUri = "file:///source/cover.jpg",
            localFilePath = "/source/cover.jpg",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        val target = source.copy(
            reference = "content://target/cover",
            mediaUri = "content://target/cover",
            localFilePath = null
        )
        val sourceRef = ManagedMigrationEntryRef(COVER_SUBDIRECTORY, source)
        val emptyTarget = ManagedMigrationTargetIndex(
            rootEntriesByName = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap()
        )
        val generated = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = emptyTarget
        )

        val restored = ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
            entries = listOf(sourceRef),
            targetIndex = emptyTarget.copy(
                coverEntriesByName = mapOf(target.name to target)
            ),
            generatedPlan = generated,
            persistedTargetNames = mapOf(source.reference to target.name)
        )

        assertEquals(target.name, restored?.targetNameFor(sourceRef))
        assertEquals(target, restored?.reusedTargetFor(sourceRef))
        assertEquals(
            null,
            ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                entries = listOf(sourceRef),
                targetIndex = emptyTarget,
                generatedPlan = generated,
                persistedTargetNames = mapOf(source.reference to "../escape.jpg")
            )
        )
    }

    @Test
    fun `tree migration target resolver reuses numbered metadata`() {
        val audioName = "言って。 - Neri - 言って。 - netease.mp3"
        val source = ManagedDownloadStorage.StoredEntry(
            name = "$audioName.npmeta.json",
            reference = "/source/$audioName.npmeta.json",
            mediaUri = "file:///source/$audioName.npmeta.json",
            localFilePath = "/source/$audioName.npmeta.json",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        val existing = source.copy(
            name = "$audioName.npmeta (2).json",
            reference = "content://target/numbered-metadata",
            mediaUri = "content://target/numbered-metadata",
            localFilePath = null
        )

        val resolved = ManagedDownloadMigrationTargetResolver.resolveTreeTarget(
            displayName = source.name,
            sourceEntry = source,
            targetNames = setOf(existing.name),
            targetEntry = null,
            existingChildEntry = existing,
            reserveName = { error("numbered metadata must be reused") },
            onReuseMetadata = {},
            onReuseFile = {}
        )

        assertFalse(resolved.createdNew)
        assertEquals(existing, resolved.entry)
    }

    @Test
    fun `migration marks same stable key with different audio size for replacement`() {
        val sourceAudio = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "/source/track.mp3",
            mediaUri = "file:///source/track.mp3",
            localFilePath = "/source/track.mp3",
            sizeBytes = 20L,
            lastModifiedMs = 1L
        )
        val targetAudio = sourceAudio.copy(
            reference = "/target/track.mp3",
            mediaUri = "file:///target/track.mp3",
            localFilePath = "/target/track.mp3",
            sizeBytes = 10L
        )
        val sourceMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-key"
        )
        val targetMetadata = sourceMetadata.copy(
            mediaUri = targetAudio.mediaUri
        )
        val sourceRef = ManagedMigrationEntryRef(null, sourceAudio)
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(sourceRef),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetAudio.name to targetAudio),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                metadataByAudioName = mapOf(targetAudio.name to targetMetadata)
            ),
            sourceMetadataByAudioName = mapOf(sourceAudio.name to sourceMetadata)
        )

        assertEquals(targetAudio.name, plan.targetNameFor(sourceRef))
        assertTrue(plan.replacementFor(sourceRef) != null)
        assertTrue(plan.conflictFor(sourceRef) == null)
    }

    @Test
    fun `migration collector keeps metadata residue when audio was already cleaned`() {
        val metadata = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npmeta.json",
            reference = "/old/Artist - Song.mp3.npmeta.json",
            mediaUri = "file:///old/Artist - Song.mp3.npmeta.json",
            localFilePath = "/old/Artist - Song.mp3.npmeta.json",
            sizeBytes = 42L,
            lastModifiedMs = 100L
        )

        val entries = ManagedDownloadMigrationEntryCollector.collect(
            rootEntries = listOf(metadata),
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            parsedMetadataByAudioName = emptyMap(),
            allowMetadataLessAudio = false
        )

        assertEquals(listOf(metadata), entries.map { it.entry })
    }

    @Test
    fun `migration collector keeps sidecars linked to metadata residue`() {
        fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "/old/$name",
            mediaUri = "file:///old/$name",
            localFilePath = "/old/$name",
            sizeBytes = 1L,
            lastModifiedMs = 100L
        )
        val metadata = entry("Artist - Song.mp3.npmeta.json")
        val cover = entry("Artist - Song.jpg")
        val lyric = entry("Artist - Song.lrc")

        val entries = ManagedDownloadMigrationEntryCollector.collect(
            rootEntries = listOf(metadata),
            coverEntries = listOf(cover),
            lyricEntries = listOf(lyric),
            parsedMetadataByAudioName = emptyMap(),
            allowMetadataLessAudio = false
        )

        assertEquals(
            setOf(metadata, cover, lyric),
            entries.map { it.entry }.toSet()
        )
    }

    @Test
    fun `migration collector keeps short legacy digest java hash and pure sha covers`() {
        fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "/old/Covers/$name",
            mediaUri = "file:///old/Covers/$name",
            localFilePath = "/old/Covers/$name",
            sizeBytes = 1L,
            lastModifiedMs = 100L
        )
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3",
            reference = "/old/Artist - Song.mp3",
            mediaUri = "file:///old/Artist%20-%20Song.mp3",
            localFilePath = "/old/Artist - Song.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 100L
        )
        val metadata = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npmeta.json",
            reference = "/old/Artist - Song.mp3.npmeta.json",
            mediaUri = "file:///old/Artist%20-%20Song.mp3.npmeta.json",
            localFilePath = "/old/Artist - Song.mp3.npmeta.json",
            sizeBytes = 10L,
            lastModifiedMs = 100L
        )
        val stableKey = "netease|123|"
        val stableCoverName = ManagedDownloadStorageNaming
            .buildStableCoverCandidateNames("Artist - Song", stableKey)
            .first()
        val stableCover = entry(stableCoverName)
        val legacyDigestCover = entry(
            "Artist - Song-${ManagedDownloadStorageNaming.stableKeySuffix(stableKey)}.jpg"
        )
        val legacyJavaHashCover = entry(
            ManagedDownloadStorageNaming
                .buildLegacyStableCoverCandidateNames("Artist - Song", stableKey)
                .first()
        )
        val pureHashCover = entry("${"c".repeat(64)}.jpg")
        val parsedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = stableKey,
            coverPath = pureHashCover.reference
        )

        val entries = ManagedDownloadMigrationEntryCollector.collect(
            rootEntries = listOf(audio, metadata),
            coverEntries = listOf(
                stableCover,
                legacyDigestCover,
                legacyJavaHashCover,
                pureHashCover
            ),
            lyricEntries = emptyList(),
            parsedMetadataByAudioName = mapOf(audio.name to parsedMetadata),
            allowMetadataLessAudio = false
        )

        assertTrue(entries.any { it.subdirectory == "Covers" && it.entry == stableCover })
        assertTrue(entries.any {
            it.subdirectory == "Covers" && it.entry == legacyDigestCover
        })
        assertTrue(entries.any {
            it.subdirectory == "Covers" && it.entry == legacyJavaHashCover
        })
        assertTrue(entries.any { it.subdirectory == "Covers" && it.entry == pureHashCover })
    }

    @Test
    fun `remote source identity marks a downloaded local song before catalog restore`() {
        val song = SongItem(
            id = 42L,
            name = "Downloaded",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/42",
            channelId = "netease",
            audioId = "42",
            sourceStableKey = "42|netease|"
        )

        assertTrue(ManagedDownloadStorage.hasManagedDownloadIdentityHint(song))
    }

    @Test
    fun `local source identity does not classify a manually added song as downloaded`() {
        val song = SongItem(
            id = 42L,
            name = "Imported",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "/music/imported.mp3",
            channelId = "local",
            audioId = "42",
            sourceStableKey = "42|netease|"
        )

        assertFalse(ManagedDownloadStorage.hasManagedDownloadIdentityHint(song))
    }

    @Test
    fun `legacy downloaded song without stable key still uses managed lyric path`() {
        val song = SongItem(
            id = 42L,
            name = "Downloaded",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "/storage/emulated/0/neriplayer-download/Downloaded.mp3",
            channelId = null,
            audioId = null,
            sourceStableKey = null
        )

        assertTrue(ManagedDownloadStorage.hasManagedDownloadIdentityHint(song))
    }

    @Test
    fun `manual song in legacy download directory stays on local lyric path`() {
        val song = SongItem(
            id = 42L,
            name = "Imported",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "/storage/emulated/0/neriplayer-download/Imported.mp3",
            channelId = "local",
            audioId = "42",
            sourceStableKey = null
        )

        assertFalse(ManagedDownloadStorage.hasManagedDownloadIdentityHint(song))
    }

    @Test
    fun `legacy download root matches media store relative paths with a parent directory`() {
        assertTrue(
            ManagedDownloadStorage.isManagedDownloadRelativePath(
                relativePath = "Music/neriplayer-download/",
                treeDocumentId = null
            )
        )
        assertFalse(
            ManagedDownloadStorage.isManagedDownloadRelativePath(
                relativePath = "Music/other-directory/",
                treeDocumentId = null
            )
        )
    }

    @Test
    fun `download document uri recovers its SAF tree root before settings restore`() {
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3Aneriplayer-download",
            ManagedDownloadStorage.managedDownloadTreeReference(
                "content://com.android.externalstorage.documents/tree/primary%3Aneriplayer-download/" +
                    "document/primary%3Aneriplayer-download%2Ftrack.mp3"
            )
        )
        assertEquals(
            null,
            ManagedDownloadStorage.managedDownloadTreeReference(
                "content://media/external_primary/audio/media/42"
            )
        )
    }

    @Test
    fun `non content and unrelated document references do not produce a tree root`() {
        assertEquals(
            null,
            ManagedDownloadStorage.managedDownloadTreeReference(
                "/storage/emulated/0/neriplayer-download/track.mp3"
            )
        )
        assertEquals(
            null,
            ManagedDownloadStorage.managedDownloadTreeReference(
                "content://com.android.externalstorage.documents/document/primary%3AMusic%2Ftrack.mp3"
            )
        )
    }

    @Test
    fun `tree reference parser accepts encoded and case insensitive content uris`() {
        assertEquals(
            "content://provider/tree/primary%3Aneriplayer-download",
            ManagedDownloadStorage.managedDownloadTreeReference(
                "CONTENT://provider/TREE/primary%3Aneriplayer-download/document/track"
            )
        )
    }

    @Test
    fun `rewriteManagedMetadataReferences remaps migrated sidecar references`() {
        val raw = JSONObject().apply {
            put("coverPath", "old://cover")
            put("coverUrl", "old://cover")
            put("originalCoverUrl", "old://cover")
            put("lyricPath", "old://lyric")
            put("translatedLyricPath", "old://translated")
            put("mediaUri", "old://audio")
            put("stableKey", "42|__local_files__|old://audio")
            put("restorableMetadata", JSONObject().apply {
                put("baseline", JSONObject().apply {
                    put("coverReference", "old://cover")
                })
                put("overrides", JSONObject().apply {
                    put("coverReference", "old://cover")
                })
            })
        }.toString()

        val rewritten = ManagedDownloadStorage.rewriteManagedMetadataReferences(
            rawJson = raw,
            referenceMap = mapOf(
                "old://cover" to "new://cover",
                "old://lyric" to "new://lyric",
                "old://translated" to "new://translated",
                "old://audio" to "new://audio"
            )
        )
        val payload = JSONObject(rewritten)

        assertEquals("new://cover", payload.getString("coverPath"))
        assertEquals("new://cover", payload.getString("coverUrl"))
        assertEquals("new://cover", payload.getString("originalCoverUrl"))
        assertEquals("new://lyric", payload.getString("lyricPath"))
        assertEquals("new://translated", payload.getString("translatedLyricPath"))
        assertEquals("new://audio", payload.getString("mediaUri"))
        assertEquals("42|__local_files__|new://audio", payload.getString("stableKey"))
        assertEquals(
            "new://cover",
            payload.getJSONObject("restorableMetadata")
                .getJSONObject("baseline")
                .getString("coverReference")
        )
        assertEquals(
            "new://cover",
            payload.getJSONObject("restorableMetadata")
                .getJSONObject("overrides")
                .getString("coverReference")
        )
    }

    @Test
    fun `rewriteManagedMetadataReferences prefers complete file URI in stable key`() {
        val raw = JSONObject().apply {
            put("stableKey", "42|__local_files__|file:/old/track.mp3")
        }.toString()

        val rewritten = ManagedDownloadStorage.rewriteManagedMetadataReferences(
            rawJson = raw,
            referenceMap = linkedMapOf(
                "/old/track.mp3" to "content://target/audio",
                "file:/old/track.mp3" to "content://target/audio"
            )
        )

        assertEquals(
            "42|__local_files__|content://target/audio",
            JSONObject(rewritten).getString("stableKey")
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged keeps metadata backed audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = setOf("Artist - Song.mp3"),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged normalizes metadata unicode names`() {
        val composed = "Caf\u00E9 - Song.mp3"
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = decomposed,
                metadataAudioNames = setOf(composed),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged accepts provider numbered metadata audio`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song (1).mp3",
                metadataAudioNames = setOf("Artist - Song.mp3"),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged does not let numbered metadata claim unnumbered audio`() {
        assertFalse(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = setOf("Artist - Song (1).mp3"),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `managed audio index performs one metadata lookup per exact audio name`() {
        fun lookupCount(songCount: Int): Int {
            val probes = AtomicInteger(0)
            val names = (0 until songCount).mapTo(linkedSetOf()) { index ->
                ManagedDownloadTreeNaming.canonicalLookupName("Artist - Song $index.mp3")
            }
            val countingNames = object : AbstractSet<String>() {
                override val size: Int = names.size

                override fun iterator(): Iterator<String> = names.iterator()

                override fun contains(element: String): Boolean {
                    probes.incrementAndGet()
                    return element in names
                }
            }
            val nameIndex = ManagedDownloadManagedAudioPolicy.NameIndex(
                metadataAudioNames = countingNames,
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )

            repeat(songCount) { index ->
                assertTrue(
                    ManagedDownloadManagedAudioPolicy.shouldTreatAudioAsManaged(
                        audioName = "Artist - Song $index.mp3",
                        nameIndex = nameIndex
                    )
                )
            }
            return probes.get()
        }

        assertEquals(1_000, lookupCount(1_000))
        assertEquals(10_000, lookupCount(10_000))
    }

    @Test
    fun `shouldTreatAudioAsManaged keeps legacy sidecar backed audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = setOf("Artist - Song.jpg"),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged keeps buggy lrc txt sidecar audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = setOf("Artist - Song.lrc.txt"),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged keeps romanized lyric sidecar audio in custom directory`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = setOf("Artist - Song_roma.lrc"),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `shouldTreatAudioAsManaged skips foreign audio in custom directory`() {
        assertFalse(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = false
            )
        )
    }

    @Test
    fun `legacy upgrade may explicitly index metadata less audio`() {
        assertTrue(
            ManagedDownloadStorage.shouldTreatAudioAsManaged(
                audioName = "Artist - Legacy Song.mp3",
                metadataAudioNames = emptySet(),
                coverEntryNames = emptySet(),
                lyricEntryNames = emptySet(),
                allowMetadataLessAudio = true
            )
        )
    }

    @Test
    fun `buildLyricCandidateNames keeps lrc txt compatibility after buggy migration`() {
        assertEquals(
            listOf(
                "42.lrc",
                "42.lrc.txt",
                "Artist - Song.lrc",
                "Artist - Song.lrc.txt"
            ),
            ManagedDownloadStorage.buildLyricCandidateNames(
                songId = 42L,
                candidateBaseNames = listOf("Artist - Song"),
                translated = false
            )
        )
    }

    @Test
    fun `buildLyricCandidateNames recognizes romanized lyric compatibility names`() {
        assertEquals(
            listOf(
                "42_roma.lrc",
                "42_roma.lrc.txt",
                "42_romalrc.lrc",
                "42_romalrc.lrc.txt",
                "42_romanized.lrc",
                "42_romanized.lrc.txt",
                "Artist - Song_roma.lrc",
                "Artist - Song_roma.lrc.txt",
                "Artist - Song_romalrc.lrc",
                "Artist - Song_romalrc.lrc.txt",
                "Artist - Song_romanized.lrc",
                "Artist - Song_romanized.lrc.txt"
            ),
            ManagedDownloadStorage.buildLyricCandidateNames(
                songId = 42L,
                candidateBaseNames = listOf("Artist - Song"),
                kind = ManagedDownloadStorage.LyricKind.ROMANIZED
            )
        )
    }

    @Test
    fun `matchesManagedSubdirectoryName keeps numbered sidecar directories compatible`() {
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers", "Covers"))
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("covers", "Covers"))
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers (1)", "Covers"))
        assertTrue(ManagedDownloadStorage.matchesManagedSubdirectoryName("Lyrics (12)", "Lyrics"))
        assertFalse(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers copy", "Covers"))
        assertFalse(ManagedDownloadStorage.matchesManagedSubdirectoryName("Covers(1)", "Covers"))
        assertFalse(ManagedDownloadStorage.matchesManagedSubdirectoryName("Lyrics (x)", "Lyrics"))
    }

    @Test
    fun `tree naming normalizes unicode before provider numbering`() {
        val expected = "Café.mp3"
        val decomposed = Normalizer.normalize("Café", Normalizer.Form.NFD)

        assertEquals(
            2,
            ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
                actualName = "$decomposed (2).MP3",
                expectedName = expected
            )
        )
        assertEquals(
            0,
            ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(
                actualName = Normalizer.normalize("Covers", Normalizer.Form.NFD),
                desiredName = "Covers"
            )
        )
    }

    @Test
    fun `documentCreateMimeType preserves explicit lyric extensions`() {
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.lrc", "text/plain")
        )
        assertEquals(
            "text/plain",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.txt", "text/plain")
        )
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType(
                "Artist - Song.flac.npmeta.json",
                "application/json"
            )
        )
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType(
                "Artist - Song.flac.npmeta (2).json.npdl_pending.7",
                "application/json"
            )
        )
        assertEquals(
            "application/json",
            ManagedDownloadStorage.documentCreateMimeType("downloads-export.json", "application/json")
        )
    }

    @Test
    fun `documentCreateMimeType keeps exact audio name on SAF providers`() {
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.flac", "audio/flac")
        )
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType(
                "Artist - Song.flac.npdl_pending.7",
                "audio/flac"
            )
        )
    }

    @Test
    fun `documentCreateMimeType keeps exact cover name on SAF providers`() {
        assertEquals(
            "application/octet-stream",
            ManagedDownloadStorage.documentCreateMimeType("Artist - Song.jpg", "image/jpeg")
        )
    }

    @Test
    fun `resolveTreeStoredName prefers actual SAF display name`() {
        assertEquals(
            "Artist - Song (1).flac",
            ManagedDownloadStorage.resolveTreeStoredName(
                actualName = "Artist - Song (1).flac",
                expectedName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `resolveTreeStoredName falls back when SAF display name is missing`() {
        assertEquals(
            "Artist - Song.flac",
            ManagedDownloadStorage.resolveTreeStoredName(
                actualName = null,
                expectedName = "Artist - Song.flac"
            )
        )
        assertEquals(
            "Artist - Song.flac",
            ManagedDownloadStorage.resolveTreeStoredName(
                actualName = "",
                expectedName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `exact tree stored name rejects provider numbered replacement`() {
        assertTrue(
            ManagedDownloadTreeNaming.isExactTreeStoredName(
                actualName = "Artist - Song.mp3.npmeta.json",
                expectedName = "Artist - Song.mp3.npmeta.json"
            )
        )
        assertFalse(
            ManagedDownloadTreeNaming.isExactTreeStoredName(
                actualName = "Artist - Song.mp3.npmeta (1).json",
                expectedName = "Artist - Song.mp3.npmeta.json"
            )
        )
    }

    @Test
    fun `createUniqueName keeps desired name when no conflict exists`() {
        assertEquals(
            "Artist - Song.flac",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Other.flac"),
                desiredName = "Artist - Song.flac"
            )
        )
    }
}
