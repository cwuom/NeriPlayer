package moe.ouom.neriplayer.data.local.playlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.storage.cleanup.LocalImportCache
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImportCachePlaylistOwnershipTest : LocalPlaylistRepositoryTestSupport() {
    @Test
    fun `automatic metadata hydration owns its cover after the cleanup reference snapshot`() = runBlocking {
        val context = mockContext()
        val original = localSong(1)
        val audio = File(checkNotNull(original.localFilePath))
        assertTrue(audio.createNewFile())
        val directory = tempFolder.newFolder("local_audio_covers")
        val cover = File(directory, "${"a".repeat(64)}.jpg")
        assertTrue(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", cover))
        val modifiedAt = cover.lastModified()
        val orphan = File(directory, "${"b".repeat(64)}.jpg").apply { writeBytes(ByteArray(11)) }
        File(audio.parentFile, "${audio.name}.npmeta.json").writeText(
            Gson().toJson(mapOf("coverPath" to cover.toURI().toString()))
        )
        val mutations = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = context,
            file = File(tempFolder.root, "playlists.json"),
            syncMutationStore = mutations
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = 1L, name = "Local", songs = mutableListOf(original))))
        assertNull(repository.playlists.value.single().songs.single().coverUrl)
        assertEquals(0L, mutations.mutationVersion)

        val cache = LocalImportCache(
            directory = directory,
            readReferences = {
                val references = repository.playlists.value.flatMap { playlist ->
                    playlist.songs.flatMap { listOfNotNull(it.coverUrl, it.customCoverUrl, it.originalCoverUrl) }
                }.toSet()
                assertTrue(references.isEmpty())
                val hydrated = LocalAudioImportManager.hydrateLocalSongCoverMetadataResult(context, original).song
                assertEquals(cover.toURI().toString(), hydrated.coverUrl)
                assertEquals(modifiedAt, cover.lastModified())
                assertTrue(LocalMediaSupport.isEmbeddedCoverInUse(cover))
                repository.updateSongMetadata(original, hydrated, triggerSync = false)
                references
            },
            referenceSnapshotValid = { mutations.mutationVersion == 0L },
            fileAccessLock = LocalMediaSupport.embeddedCoverCacheLock,
            isCoverInUse = LocalMediaSupport::isEmbeddedCoverInUse,
            clearReadCaches = LocalMediaSupport::clearCoverLookupCache
        )

        assertEquals(ExtraCacheClearResult(true, 11L, 0L, 1), cache.clear())
        assertEquals(cover.toURI().toString(), repository.playlists.value.single().songs.single().coverUrl)
        assertEquals(0L, mutations.mutationVersion)
        assertTrue(cover.exists())
        assertFalse(orphan.exists())
    }
}
