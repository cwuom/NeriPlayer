package moe.ouom.neriplayer.core.player.download

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定刚提交音频的别名替换和精确引用优先级 */
class AudioDownloadManagerPlaybackRaceTest {
    @Test
    fun `replacing a completed reference removes aliases from the previous audio`() {
        val suffix = System.nanoTime().toString()
        val oldPath = "/downloads/old-$suffix.mp3"
        val newPath = "/downloads/new-$suffix.mp3"
        val oldAudio = storedEntry(oldPath)
        val newAudio = storedEntry(newPath)
        val oldSong = localSong(oldPath)
        val newSong = localSong(newPath)
        val songKey = "alias-replacement-$suffix"

        AudioDownloadManager.releaseCompletedAudioReference(songKey)
        AudioDownloadManager.rememberCompletedAudioReference(songKey, oldAudio)
        AudioDownloadManager.rememberCompletedAudioReference(songKey, newAudio)

        assertNull(AudioDownloadManager.peekCompletedAudioReference(oldSong))
        assertEquals(newAudio, AudioDownloadManager.peekCompletedAudioReference(newSong))

        AudioDownloadManager.releaseCompletedAudioReference(songKey)
    }

    @Test
    fun `completed audio is reachable through source and legacy identity aliases`() {
        val suffix = System.nanoTime().toString()
        val audio = storedEntry("/downloads/alias-$suffix.mp3")
        val sourceSong = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val queuedSong = sourceSong.copy(
            mediaUri = "content://old-tree/$suffix",
            localFilePath = audio.localFilePath,
            sourceStableKey = sourceSong.stableKey()
        )

        AudioDownloadManager.rememberCompletedAudioReference(sourceSong, audio)
        assertEquals(audio, AudioDownloadManager.peekCompletedAudioReference(queuedSong))
        assertEquals(
            audio,
            AudioDownloadManager.peekCompletedAudioReferenceByRawReference(audio.reference)
        )
        assertEquals(
            audio,
            AudioDownloadManager.peekCompletedAudioReferenceByRawReference(audio.mediaUri)
        )
        AudioDownloadManager.releaseCompletedAudioReference(sourceSong.stableKey())
    }

    @Test
    fun `completed audio is reachable when the queue temporarily exposes a local shaped song`() {
        val suffix = System.nanoTime().toString()
        val audio = storedEntry("/downloads/local-shaped-$suffix.mp3")
        val sourceSong = SongItem(
            id = 84L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "84"
        )
        val localShapedSong = sourceSong.copy(
            mediaUri = "content://stale-tree/$suffix",
            sourceStableKey = null
        )

        AudioDownloadManager.rememberCompletedAudioReference(sourceSong, audio)

        assertEquals(audio, AudioDownloadManager.peekCompletedAudioReference(localShapedSong))

        AudioDownloadManager.releaseCompletedAudioReference(sourceSong.stableKey())
    }

    @Test
    fun `pending audio uses pending metadata when formal metadata has the same name`() {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "Song.flac.npdl_pending",
            reference = "content://provider/pending/Song.flac.npdl_pending",
            mediaUri = "content://provider/pending/Song.flac.npdl_pending",
            localFilePath = null,
            sizeBytes = 10L,
            lastModifiedMs = 2L
        )
        val formalMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "1|netease|",
            downloadFinalized = true
        )
        val pendingMetadata = formalMetadata.copy(
            stableKey = "2|netease|",
            downloadFinalized = false,
            artifactState = "CORE_COMMITTED"
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            metadataByAudioName = mapOf("Song.flac" to formalMetadata),
            pendingMetadataByAudioName = mapOf("Song.flac" to pendingMetadata)
        )

        assertEquals(
            pendingMetadata,
            ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
        )
    }

    @Test
    fun `incomplete lookup checks the exact reference before song identity fallback`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "isManagedReferenceExplicitlyIncomplete")
        val exactIndex = body.indexOf("val exactAudio")
        val identityIndex = body.indexOf("ManagedDownloadStorage.findDownloadedAudio(snapshot, song)")

        assertTrue(exactIndex >= 0)
        assertTrue(identityIndex > exactIndex)
        assertTrue(body.contains("snapshot.pendingAudioEntries.firstOrNull(::matchesReference)"))
    }

    @Test
    fun `finalization uses the SongItem alias bridge`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "finalizeCompletedDownload")

        assertTrue(body.contains("peekCompletedAudioReference(song)"))
    }

    @Test
    fun `recent bridge bypasses synchronous provider inspection`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "resolveRecentlyCommittedAudioReference")

        assertTrue(body.contains("peekCompletedAudioReferenceByRawReference"))
        assertTrue(body.contains("shouldUseCompletedAudioReferenceDirectly"))
        assertTrue(body.contains("GlobalDownloadManager.scanLocalFiles"))
        assertFalse(body.contains("ManagedDownloadReferenceLookup.inspect"))
    }

    private fun storedEntry(path: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = File(path).name,
            reference = path,
            mediaUri = "file://$path",
            localFilePath = path,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }

    private fun localSong(path: String): SongItem {
        return SongItem(
            id = 0L,
            name = File(path).nameWithoutExtension,
            artist = "artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 1L,
            coverUrl = null,
            mediaUri = "file://$path",
            localFilePath = path
        )
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).find(source)?.range?.first
            ?: error("method not found: $methodName")
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $methodName")
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
