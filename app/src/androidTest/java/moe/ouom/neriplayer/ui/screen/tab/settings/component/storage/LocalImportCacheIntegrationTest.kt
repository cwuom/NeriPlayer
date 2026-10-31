package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.storage.analyzeStorageUsage
import moe.ouom.neriplayer.data.local.storage.clearExtraStorageCaches
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImportCacheIntegrationTest {
    @Test
    fun cleanupRemovesAnOrphanAndPreservesTheLiveQueueCover() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "local_audio_covers").apply { mkdirs() }
        val orphan = File(directory, "${"a".repeat(64)}.jpg")
        val queued = File(directory, "${"b".repeat(64)}.jpg")
        check(!orphan.exists() && !queued.exists())
        try {
            orphan.writeBytes(ByteArray(7))
            queued.writeBytes(ByteArray(11))
            val song = SongItem(606, "track", "artist", "album", 0, 100, queued.toURI().toString())
            analyzeStorageUsage(context, additionalSongsProvider = { listOf(song) })

            val result = clearExtraStorageCaches(
                context,
                StorageCacheClearOptions(audioCache = false, imageCache = false, localImportCache = true),
                additionalSongsProvider = { listOf(song) }
            )

            assertEquals(ExtraCacheClearResult(true, 7, 0, 1), result)
            assertFalse(orphan.exists())
            assertTrue(queued.exists())
        } finally {
            orphan.delete()
            queued.delete()
        }
    }
}
