package moe.ouom.neriplayer.data.sync.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncArchiveAndroidTest {
    @Test fun packagedNativeCodecRoundTripsOnAndroidAndRejectsCorruption() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "sync-v3-instrumented-${System.nanoTime()}")
        try {
            val repository = SyncArchiveRepository(directory)
            val data = SyncData(deviceId = "synthetic", lastModified = 1,
                playlists = listOf(SyncPlaylist(id = 7, songs = (1L..10_000).map {
                    SyncSong(id = it, name = "合成歌曲-$it", lyricSyncEdited = false)
                })))
            repository.prepare(data).use { archive ->
                assertEquals(data, repository.read(archive.content) { error("complete native cache") }.getOrThrow())
                val corrupted = archive.content.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
                assertTrue(repository.read(corrupted) { error("invalid root must fail before fetching") }.isFailure)
            }
        } finally { directory.deleteRecursively() }
    }
}
