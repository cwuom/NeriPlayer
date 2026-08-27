package moe.ouom.neriplayer.core.download

import android.content.Context
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationFinalizer
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetResolver
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntryRef
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.MIGRATION_PROGRESS_EMIT_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
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
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

class ManagedDownloadStorageMigrationCompatTest {

    private fun deleteFile(reference: TrustedManagedRef): StorageMutationResult {
        val fileReference = reference.reference as? StorageReference.FileRef
            ?: return StorageMutationResult.Unsupported("file reference required")
        val file = File(fileReference.logicalPath)
        return if (!file.exists()) {
            StorageMutationResult.Missing
        } else if (file.delete()) {
            StorageMutationResult.Deleted
        } else {
            StorageMutationResult.ProviderFailure(IOException("delete failed"))
        }
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
    fun `migration marks same stable key with different audio size as conflict`() {
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

        assertTrue(plan.conflictFor(sourceRef)?.contains("different audio sizes") == true)
        assertTrue(plan.reusedTargetFor(sourceRef) == null)
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

    @Test
    fun `createUniqueName treats canonically equivalent unicode names as a conflict`() {
        val decomposed = "Cafe\u0301 - Artist.mp3"
        assertEquals(
            "Cafe\u0301 - Artist (1).mp3",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Café - Artist.mp3"),
                desiredName = decomposed
            )
        )
    }

    @Test
    fun `createUniqueName increments numbered suffix on conflict`() {
        assertEquals(
            "Artist - Song (2).flac",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf(
                    "Artist - Song.flac",
                    "Artist - Song (1).flac"
                ),
                desiredName = "Artist - Song.flac"
            )
        )
    }

    @Test
    fun `createUniqueName supports extensionless names`() {
        assertEquals(
            "Artist - Song (1)",
            ManagedDownloadStorage.createUniqueName(
                existingNames = setOf("Artist - Song"),
                desiredName = "Artist - Song"
            )
        )
    }

    @Test
    fun `parseDownloadedAudioMetadataJson keeps embedded lyrics for local fallback`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("matchedLyric", "[00:00.00]原文")
                put("matchedTranslatedLyric", "[00:00.00]翻译")
                put("originalLyric", "[00:00.00]原始原文")
                put("originalTranslatedLyric", "[00:00.00]原始翻译")
                put("lyricPath", "/music/Lyrics/Artist - Song.lrc")
            }.toString()
        )

        assertEquals("[00:00.00]原文", metadata?.matchedLyric)
        assertEquals("[00:00.00]翻译", metadata?.matchedTranslatedLyric)
        assertEquals("[00:00.00]原始原文", metadata?.originalLyric)
        assertEquals("[00:00.00]原始翻译", metadata?.originalTranslatedLyric)
        assertEquals("/music/Lyrics/Artist - Song.lrc", metadata?.lyricPath)
    }

    @Test
    fun `download metadata preserves original download time`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("downloadTimeMs", 123456789L)
            }.toString()
        )

        assertEquals(123456789L, metadata?.downloadTimeMs)
    }

    @Test
    fun `download metadata restores immutable created at fields`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("schemaVersion", 3)
                put("createdAtMs", 123456789L)
                put("createdAtSource", "MANAGED_COMMIT")
            }.toString()
        )

        assertEquals(123456789L, metadata?.createdAtMs)
        assertEquals("MANAGED_COMMIT", metadata?.createdAtSource)
    }

    @Test
    fun `parseDownloadedAudioMetadataJson keeps explicit cleared lyrics as blank string`() {
        val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(
            JSONObject().apply {
                put("matchedLyric", "")
                put("matchedTranslatedLyric", "")
                put("originalLyric", "")
                put("originalTranslatedLyric", "")
            }.toString()
        )

        assertEquals("", metadata?.matchedLyric)
        assertEquals("", metadata?.matchedTranslatedLyric)
        assertEquals("", metadata?.originalLyric)
        assertEquals("", metadata?.originalTranslatedLyric)
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch keeps source when copied size is unknown or empty`() {
        // #D3 回归: 目标尺寸为 0 (SAF 对新建文档常返回 length=0) 时必须保留源, 避免误删导致数据丢失
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 0L
            )
        )
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 0L
            )
        )
        // 防御性: 负数 (不可知) 同样保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = -1L
            )
        )
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch keeps source when target is truncated or size mismatches`() {
        // 目标非空但明显小于源 (截断/损坏) 时保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 1L
            )
        )
        // 源尺寸不可知(0) 但目标非空且远超容差, 视为不一致, 保留源
        assertTrue(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 100L
            )
        )
    }

    @Test
    fun `shouldKeepSourceForSizeMismatch allows deleting source when copy faithfully matches`() {
        // 源/目标尺寸一致 (容差内) 时确认拷贝可信, 允许删源
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 100L
            )
        )
        // 容差为 1 字节, 相差 1 仍视为一致
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 100L,
                copiedSize = 101L
            )
        )
        // 源本就为空(0), 目标落在容差内(1), 视为一致, 允许删源 (源本就为空才可删)
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForSizeMismatch(
                sourceSize = 0L,
                copiedSize = 1L
            )
        )
    }

    @Test
    fun `migration verifies content with streaming sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ManagedDownloadMigrationFinalizer.sha256Hex(
                ByteArrayInputStream("abc".toByteArray())
            )
        )
    }

    @Test
    fun `directory changes always require explicit migration confirmation`() {
        assertTrue(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = null,
                toDirectoryUri = "content://provider/tree/downloads"
            )
        )
        assertFalse(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = "content://provider/tree/downloads",
                toDirectoryUri = "content://provider/tree/downloads"
            )
        )
        assertTrue(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = "content://provider/tree/downloads",
                toDirectoryUri = null
            )
        )
    }

    @Test
    fun `fresh install reattaches a populated managed SAF directory instead of migrating empty private root`() {
        assertTrue(
            ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
                fromDirectoryUri = null,
                toDirectoryUri = "content://provider/tree/downloads",
                sourceHasManagedEntries = false,
                targetHasManagedEntries = true
            )
        )
        assertTrue(
            ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
                fromDirectoryUri = null,
                toDirectoryUri = "content://provider/tree/downloads",
                sourceHasManagedEntries = null,
                targetHasManagedEntries = true
            )
        )
        assertFalse(
            ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
                fromDirectoryUri = "content://provider/tree/private",
                toDirectoryUri = "content://provider/tree/downloads",
                sourceHasManagedEntries = false,
                targetHasManagedEntries = true
            )
        )
    }

    @Test
    fun `migration scan retry classification keeps unavailable source permanent`() {
        assertFalse(
            ManagedDownloadMigrationException.permanent("source unavailable").retryable
        )
        assertTrue(
            ManagedDownloadMigrationException.transient("incomplete SAF enumeration").retryable
        )
    }

    @Test
    fun `migration does not reuse an unknown same-name metadata target`() {
        val source = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3.npmeta.json",
            reference = "/source/track.mp3.npmeta.json",
            mediaUri = "file:///source/track.mp3.npmeta.json",
            localFilePath = "/source/track.mp3.npmeta.json",
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
        val target = source.copy(
            reference = "/target/track.mp3.npmeta.json",
            mediaUri = "file:///target/track.mp3.npmeta.json",
            localFilePath = "/target/track.mp3.npmeta.json"
        )

        val directory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            val resolved = ManagedDownloadMigrationTargetResolver.resolveFileTarget(
                parent = directory,
                displayName = source.name,
                sourceEntry = source,
                targetNames = setOf(source.name),
                targetEntry = target,
                readExistingEntry = { target },
                reserveName = { "$it (1)" },
                onReuseMetadata = {},
                onReuseFile = {}
            )

            assertTrue(resolved.createdNew)
            assertEquals("${source.name} (1)", resolved.entry.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration reserves even an initially available target name`() {
        val source = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "/source/track.mp3",
            mediaUri = "file:///source/track.mp3",
            localFilePath = "/source/track.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
        val directory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            var reservedName: String? = null
            val resolved = ManagedDownloadMigrationTargetResolver.resolveFileTarget(
                parent = directory,
                displayName = source.name,
                sourceEntry = source,
                targetNames = emptySet(),
                targetEntry = null,
                readExistingEntry = { null },
                reserveName = { name ->
                    reservedName = name
                    name
                },
                onReuseMetadata = {},
                onReuseFile = {}
            )

            assertTrue(resolved.createdNew)
            assertEquals(source.name, reservedName)
            assertEquals(source.name, resolved.entry.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration hashes source when its provider does not report a size`() {
        assertFalse(
            ManagedDownloadMigrationFinalizer.shouldKeepSourceForMigrationSize(
                sourceSize = 0L,
                copiedSize = 100L
            )
        )
    }

    @Test
    fun `migration switches directory after copies succeed even when source cleanup needs retry`() {
        val result = ManagedDownloadStorage.MigrationResult(
            movedFiles = 2,
            skippedFiles = 0,
            cleanupFailedFiles = 1,
            cleanupRetryableFailedFiles = 1
        )

        assertTrue(result.canSwitchDirectory)
        assertFalse(result.canReleasePreviousPermission)
        assertTrue(result.hasOnlyRetryableCleanupFailures)
    }

    @Test
    fun `migration reuses target audio when metadata identifies the same song`() {
        val sourceAudio = ManagedDownloadStorage.StoredEntry(
            name = "source.mp3",
            reference = "/old/source.mp3",
            mediaUri = "file:///old/source.mp3",
            localFilePath = "/old/source.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 100L
        )
        val targetAudio = ManagedDownloadStorage.StoredEntry(
            name = "target.mp3",
            reference = "/new/target.mp3",
            mediaUri = "file:///new/target.mp3",
            localFilePath = "/new/target.mp3",
            sizeBytes = 10L,
            lastModifiedMs = 200L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "42|netease|track-42",
            songId = 42L,
            identityAlbum = "netease"
        )
        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(
                ManagedMigrationEntryRef(null, sourceAudio)
            ),
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = mapOf(targetAudio.name to targetAudio),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                metadataByAudioName = mapOf(targetAudio.name to metadata)
            ),
            sourceMetadataByAudioName = mapOf(sourceAudio.name to metadata)
        )

        assertEquals(targetAudio.name, plan.targetNameFor(ManagedMigrationEntryRef(null, sourceAudio)))
        assertEquals(
            targetAudio,
            plan.reusedTargetFor(ManagedMigrationEntryRef(null, sourceAudio))
        )
    }

    @Test
    fun `migration keeps unknown size non-audio source when copied content differs`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-sidecar-migration").toFile()
        try {
            val sourceFile = File(directory, "source-cover.jpg").apply {
                writeText("original-cover")
            }
            val targetFile = File(directory, "target-cover.jpg").apply {
                writeText("cut")
            }
            val sourceEntry = ManagedDownloadStorage.StoredEntry(
                name = sourceFile.name,
                reference = sourceFile.absolutePath,
                mediaUri = sourceFile.toURI().toString(),
                localFilePath = sourceFile.absolutePath,
                sizeBytes = 0L,
                lastModifiedMs = 1L
            )
            val targetEntry = ManagedDownloadStorage.StoredEntry(
                name = targetFile.name,
                reference = targetFile.absolutePath,
                mediaUri = targetFile.toURI().toString(),
                localFilePath = targetFile.absolutePath,
                sizeBytes = targetFile.length(),
                lastModifiedMs = 2L
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                openInputStream = { _, entry -> File(entry.reference).inputStream() },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = listOf(
                    CopiedMigrationEntry(
                        original = ManagedMigrationEntry(null, sourceEntry),
                        copiedEntry = targetEntry,
                        createdNew = true
                    )
                ),
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
            )

            assertEquals(1, cleanupFailures)
            assertTrue(sourceFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration does not invoke destructive cleanup before target verification`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-migration-unverified").toFile()
        try {
            val sourceFile = File(directory, "source.mp3").apply { writeText("source") }
            val targetFile = File(directory, "target.mp3").apply { writeText("different") }
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val deleteCalls = AtomicInteger(0)
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                openInputStream = { _, entry -> File(entry.reference).inputStream() },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, _, _ ->
                    deleteCalls.incrementAndGet()
                    StorageMutationResult.Deleted
                },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = listOf(
                    CopiedMigrationEntry(
                        original = ManagedMigrationEntry(null, entry(sourceFile)),
                        copiedEntry = entry(targetFile),
                        createdNew = true
                    )
                ),
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
            )

            assertEquals(1, cleanupFailures)
            assertEquals(0, deleteCalls.get())
            assertTrue(sourceFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration keeps source when permission or provider cleanup fails`() = runBlocking {
        listOf(
            StorageMutationResult.PermissionLost,
            StorageMutationResult.ProviderFailure(IOException("provider unavailable"))
        ).forEach { deleteResult ->
            val directory = Files.createTempDirectory("neriplayer-migration-delete-failure").toFile()
            try {
                val sourceFile = File(directory, "source.mp3").apply { writeText("same") }
                val targetFile = File(directory, "target.mp3").apply { writeText("same") }
                fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                    name = file.name,
                    reference = file.absolutePath,
                    mediaUri = file.toURI().toString(),
                    localFilePath = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified()
                )
                val finalizer = ManagedDownloadMigrationFinalizer(
                    tag = "ManagedDownloadStorageMigrationCompatTest",
                    rewriteParallelism = { 1 },
                    deleteParallelism = { 1 },
                    readText = { _, _ -> null },
                    openInputStream = { _, entry -> File(entry.reference).inputStream() },
                    writeRootText = { _, _, _, _ -> null },
                    deleteReference = { _, _, _ -> deleteResult },
                    rewriteMetadataReferences = { raw, _ -> raw }
                )

                val cleanupResult = finalizer.cleanupMigratedEntriesDetailed(
                    context = mock(Context::class.java),
                    copiedEntries = listOf(
                        CopiedMigrationEntry(
                            original = ManagedMigrationEntry(null, entry(sourceFile)),
                            copiedEntry = entry(targetFile),
                            createdNew = true
                        )
                    ),
                    sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
                )

                assertEquals(1, cleanupResult.failedFiles)
                assertEquals(
                    if (deleteResult is StorageMutationResult.ProviderFailure) 1 else 0,
                    cleanupResult.retryableFailedFiles
                )
                assertTrue(sourceFile.exists())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `migration removes verified source when typed cleanup is confirmed`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-migration-delete-success").toFile()
        try {
            val sourceFile = File(directory, "source.mp3").apply { writeText("same") }
            val targetFile = File(directory, "target.mp3").apply { writeText("same") }
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                openInputStream = { _, entry -> File(entry.reference).inputStream() },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = { raw, _ -> raw }
            )

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = listOf(
                    CopiedMigrationEntry(
                        original = ManagedMigrationEntry(null, entry(sourceFile)),
                        copiedEntry = entry(targetFile),
                        createdNew = true
                    )
                ),
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory)
            )

            assertEquals(0, cleanupFailures)
            assertFalse(sourceFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration reports cleanup before deletion and advances failed attempts once`() = runBlocking {
        val directory = Files.createTempDirectory("neriplayer-migration-cleanup-progress").toFile()
        try {
            val sourceFiles = listOf(
                File(directory, "source-a.mp3").apply { writeText("a") },
                File(directory, "source-b.mp3").apply { writeText("b") }
            )
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val progressUpdates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
            val reporter = ManagedMigrationProgressReporter(
                totalFiles = sourceFiles.size,
                totalBytes = sourceFiles.sumOf(File::length),
                metadataFilesTotal = 0,
                onProgress = progressUpdates::add
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, _ -> null },
                openInputStream = { _, storedEntry -> File(storedEntry.reference).inputStream() },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, _, _ -> StorageMutationResult.Deleted },
                deleteReferences = {
                        _,
                        references,
                        _,
                        onDeleteStarted,
                        onDeleteFinished ->
                    assertEquals(
                        ManagedDownloadStorage.MigrationStage.CLEANING_UP,
                        progressUpdates.last().stage
                    )
                    assertEquals(0, progressUpdates.last().cleanupFilesProcessed)
                    references.mapIndexed { index, reference ->
                        onDeleteStarted(reference)
                        Thread.sleep(MIGRATION_PROGRESS_EMIT_INTERVAL_MS + 20L)
                        onDeleteFinished(reference)
                        onDeleteFinished(reference)
                        reference to if (index == 0) {
                            StorageMutationResult.Deleted
                        } else {
                            StorageMutationResult.PermissionLost
                        }
                    }.toMap()
                },
                rewriteMetadataReferences = { raw, _ -> raw }
            )
            val copiedEntries = sourceFiles.map { sourceFile ->
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceFile)),
                    copiedEntry = entry(sourceFile),
                    createdNew = true
                )
            }

            val cleanupFailures = finalizer.cleanupMigratedEntries(
                context = mock(Context::class.java),
                copiedEntries = copiedEntries,
                sourceRoot = ManagedDownloadRootHandle.FileRoot(directory),
                targetsAlreadyVerified = true,
                progressTracker = reporter
            )

            assertEquals(1, cleanupFailures)
            assertTrue(progressUpdates.any { it.cleanupFilesProcessed == 1 })
            assertEquals(2, progressUpdates.last().cleanupFilesProcessed)
            assertEquals(2, progressUpdates.last().cleanupFilesTotal)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration verifies rewritten metadata before deleting either source root`() = runBlocking {
        val sourceDirectory = Files.createTempDirectory("neriplayer-migration-source").toFile()
        val targetDirectory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            val sourceAudio = File(sourceDirectory, "track.mp3").apply { writeText("audio") }
            val targetAudio = File(targetDirectory, "track.mp3").apply { writeText("audio") }
            val sourceMetadata = File(sourceDirectory, "track.mp3.npmeta.json")
            val targetMetadata = File(targetDirectory, "track.mp3.npmeta.json")
            val sourceMetadataText = JSONObject().apply {
                put("mediaUri", sourceAudio.toURI().toString())
                put("stableKey", "1|local|${sourceAudio.toURI()}")
            }.toString()
            sourceMetadata.writeText(sourceMetadataText)
            targetMetadata.writeText(
                ManagedDownloadStorage.rewriteManagedMetadataReferences(
                    rawJson = sourceMetadataText,
                    referenceMap = mapOf(
                        sourceAudio.absolutePath to targetAudio.absolutePath,
                        sourceAudio.toURI().toString() to targetAudio.toURI().toString()
                    )
                )
            )
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString(),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val copiedEntries = listOf(
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceAudio)),
                    copiedEntry = entry(targetAudio),
                    createdNew = true,
                    sourceDigest = sourceAudio.inputStream().use(
                        ManagedDownloadMigrationFinalizer::sha256Hex
                    )
                ),
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceMetadata)),
                    copiedEntry = entry(targetMetadata),
                    createdNew = true
                )
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, reference -> File(reference).readText() },
                openInputStream = { _, entry -> File(entry.reference).inputStream() },
                writeRootText = { _, _, _, _ -> null },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = ManagedDownloadStorage::rewriteManagedMetadataReferences
            )

            assertEquals(
                0,
                finalizer.verifyMigratedEntries(
                    context = mock(Context::class.java),
                    targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                    copiedEntries = copiedEntries
                )
            )

            targetMetadata.writeText("{}")
            assertEquals(
                1,
                finalizer.verifyMigratedEntries(
                    context = mock(Context::class.java),
                    targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                    copiedEntries = copiedEntries
                )
            )
            assertTrue(sourceAudio.exists())
            assertTrue(sourceMetadata.exists())
        } finally {
            sourceDirectory.deleteRecursively()
            targetDirectory.deleteRecursively()
        }
    }

    @Test
    fun `migration rewrites metadata left by an interrupted copy`() = runBlocking {
        val sourceDirectory = Files.createTempDirectory("neriplayer-migration-source").toFile()
        val targetDirectory = Files.createTempDirectory("neriplayer-migration-target").toFile()
        try {
            val sourceAudio = File(sourceDirectory, "track.mp3").apply { writeText("audio") }
            val targetAudio = File(targetDirectory, "track.mp3").apply { writeText("audio") }
            val sourceMetadata = File(sourceDirectory, "track.mp3.npmeta.json")
            val targetMetadata = File(targetDirectory, "track.mp3.npmeta.json")
            val sourceMetadataText = JSONObject().apply {
                put("mediaUri", sourceAudio.toURI().toString())
                put("localFilePath", sourceAudio.absolutePath)
                put("stableKey", "1|local|${sourceAudio.toURI()}")
            }.toString()
            sourceMetadata.writeText(sourceMetadataText)
            targetMetadata.writeText(sourceMetadataText)
            val providerRewrittenMetadata = File(
                targetDirectory,
                "track.provider-rewritten.mp3.npmeta.json"
            )
            fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
                name = file.name,
                reference = file.absolutePath,
                mediaUri = file.toURI().toString().replaceFirst("file:", "file://"),
                localFilePath = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
            val copiedEntries = listOf(
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceAudio)),
                    copiedEntry = entry(targetAudio),
                    createdNew = false
                ),
                CopiedMigrationEntry(
                    original = ManagedMigrationEntry(null, entry(sourceMetadata)),
                    copiedEntry = entry(targetMetadata),
                    createdNew = false
                )
            )
            val finalizer = ManagedDownloadMigrationFinalizer(
                tag = "ManagedDownloadStorageMigrationCompatTest",
                rewriteParallelism = { 1 },
                deleteParallelism = { 1 },
                readText = { _, reference -> File(reference).readText() },
                openInputStream = { _, entry -> File(entry.reference).inputStream() },
                writeRootText = { _, _, _, content ->
                    providerRewrittenMetadata.apply { writeText(content) }.let(::entry)
                },
                deleteReference = { _, reference, _ -> deleteFile(reference) },
                rewriteMetadataReferences = ManagedDownloadStorage::rewriteManagedMetadataReferences
            )

            val rewriteResult = finalizer.rewriteMigratedMetadataReferences(
                context = mock(Context::class.java),
                targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                copiedEntries = copiedEntries
            )
            assertEquals(0, rewriteResult.failedFiles)
            val rewrittenMetadata = rewriteResult.copiedEntries.last().copiedEntry
            assertEquals(providerRewrittenMetadata.absolutePath, rewrittenMetadata.reference)
            val rewritten = JSONObject(File(rewrittenMetadata.reference).readText())
            assertEquals(targetAudio.toURI().toString(), rewritten.getString("mediaUri"))
            assertEquals(targetAudio.absolutePath, rewritten.getString("localFilePath"))
            assertEquals("1|local|${targetAudio.toURI()}", rewritten.getString("stableKey"))
            assertEquals(
                0,
                finalizer.verifyMigratedEntries(
                    context = mock(Context::class.java),
                    targetRoot = ManagedDownloadRootHandle.FileRoot(targetDirectory),
                    copiedEntries = rewriteResult.copiedEntries
                )
            )
        } finally {
            sourceDirectory.deleteRecursively()
            targetDirectory.deleteRecursively()
        }
    }

    @Test
    fun `private migration commit copies sixteen MiB within ten seconds`() {
        val directory = Files.createTempDirectory("neriplayer-migration-performance").toFile()
        val payload = ByteArray(512 * 1024) { index -> (index % 251).toByte() }
        try {
            val elapsedMs = measureTimeMillis {
                repeat(32) { index ->
                    ManagedDownloadCommitIo.copyFileAtomically(
                        parent = directory,
                        targetName = "track-$index.mp3",
                        input = ByteArrayInputStream(payload),
                        bufferSizeBytes = 64 * 1024
                    )
                }
            }

            assertTrue("private migration took ${elapsedMs}ms", elapsedMs < 10_000L)
            assertEquals(32, directory.listFiles()?.count(File::isFile))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `atomic migration copy removes partial file after source failure`() {
        val directory = Files.createTempDirectory("neriplayer-migration-test").toFile()
        try {
            val failingInput = object : InputStream() {
                private var readCalls = 0

                override fun read(): Int {
                    throw IOException("single-byte read should not be used")
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readCalls++ == 0) {
                        buffer[offset] = 1
                        return 1
                    }
                    throw IOException("injected source failure")
                }
            }
            runCatching {
                ManagedDownloadCommitIo.copyFileAtomically(
                    parent = directory,
                    targetName = "song.mp3",
                    input = failingInput,
                    bufferSizeBytes = 8,
                    onProgress = {}
                )
            }
            assertFalse(File(directory, "song.mp3").exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".partial") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
