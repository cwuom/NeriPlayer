package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_FILE_NAME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadSnapshotDiskCacheDeleteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @Before
    fun setUp() {
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
    }

    @Test
    fun `delete removes the current and legacy snapshot caches`() {
        val current = cacheFile(CURRENT_SNAPSHOT_CACHE_FILE_NAME).apply { writeText("{}") }
        val legacy = cacheFile(SNAPSHOT_CACHE_FILE_NAME).apply { writeText("{}") }

        ManagedDownloadSnapshotDiskCache.delete(context)

        assertFalse(current.exists())
        assertFalse(legacy.exists())

        ManagedDownloadSnapshotDiskCache.delete(context)

        assertFalse(current.exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun `an undeletable current cache does not stop legacy cache cleanup`() {
        val current = cacheFile(CURRENT_SNAPSHOT_CACHE_FILE_NAME).apply {
            mkdirs()
            resolve("entry.json").writeText("{}")
        }
        val legacy = cacheFile(SNAPSHOT_CACHE_FILE_NAME).apply { writeText("{}") }

        ManagedDownloadSnapshotDiskCache.delete(context)

        assertTrue(current.resolve("entry.json").isFile)
        assertFalse(legacy.exists())
    }

    private fun cacheFile(name: String) = File(temporaryFolder.root, name)
}
