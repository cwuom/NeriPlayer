@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package moe.ouom.neriplayer.core.player.cache

import coil.ImageLoader
import coil.disk.DiskCache
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RetainedImageCacheClearTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `an editor opened during clear remains tracked for the next cleanup`() = runTest {
        val delegate = realCache()
        val cleared = CountDownLatch(1)
        val finishClear = CountDownLatch(1)
        val disk = track(object : DiskCache by delegate {
            override fun clear() {
                delegate.clear()
                cleared.countDown()
                check(finishClear.await(5, TimeUnit.SECONDS))
            }
        })
        val clearThread = thread { disk.clear() }
        check(cleared.await(5, TimeUnit.SECONDS))
        val editorStarted = CountDownLatch(1)
        val editorDone = CountDownLatch(1)
        val writerThread = thread {
            editorStarted.countDown()
            write(disk, "new-cover")
            editorDone.countDown()
        }
        try {
            check(editorStarted.await(5, TimeUnit.SECONDS))
            editorDone.await(250, TimeUnit.MILLISECONDS)
        } finally {
            finishClear.countDown()
            clearThread.join(5000)
            writerThread.join(5000)
        }
        assertFalse(clearThread.isAlive)
        assertFalse(writerThread.isAlive)
        clearRetainingImageCache(loader(disk), setOf("retained"))
        assertNull(disk.openSnapshot("new-cover"))
    }

    @Test
    fun `mini player and playlist cover keys retain only their exact cover URL suffix`() = runTest {
        val disk = track(realCache())
        val url = "https://cover.example/a"
        val miniPlayerKey = "netease:42|data=$url"
        val playlistKey = "playlist-row-cover|playlist:7|$url"
        val unrelated = "netease:43|data=${url}b"
        val unrelatedPlaylist = "playlist-row-cover|playlist:8|${url}b"
        listOf(miniPlayerKey, playlistKey, unrelated, unrelatedPlaylist).forEach { write(disk, it) }

        val result = clearRetainingImageCache(loader(disk), setOf(url))

        assertTrue(result.success)
        assertEntry(disk, miniPlayerKey)
        assertEntry(disk, playlistKey)
        assertNull(disk.openSnapshot(unrelated))
        assertNull(disk.openSnapshot(unrelatedPlaylist))
    }

    @Test
    fun `legacy untracked versioned covers remain available when retaining recent songs`() = runTest {
        val delegate = realCache()
        val url = "https://cover.example/a"
        val legacyKey = "nowplaying-cover|$url|1|2|3|data=$url"
        write(delegate, legacyKey)
        val disk = track(delegate)
        write(disk, "https://cover.example/b")

        val result = clearRetainingImageCache(loader(disk), setOf(url))

        assertTrue(result.success)
        assertEntry(disk, legacyKey)
        assertNull(disk.openSnapshot("https://cover.example/b"))
    }

    @Test
    fun `clear retains raw and tracked versioned cover entries with their metadata`() = runTest {
        val disk = track(realCache())
        val url = "https://cover.example/a"
        val coverKey = "nowplaying-cover|$url|2|3|4|data=$url"
        val blurKey = "nowplaying-blur:$url:4.0:revision"
        listOf(url, coverKey, blurKey, "https://cover.example/b").forEach { write(disk, it) }

        val result = clearRetainingImageCache(loader(disk), setOf(url))

        assertTrue(result.success)
        listOf(url, coverKey, blurKey).forEach { assertEntry(disk, it) }
        assertNull(disk.openSnapshot("https://cover.example/b"))
        assertTrue(result.freedBytes > 0)
        assertEquals(0L, result.roomBytesMadeReusable)
        assertEquals(2, result.deletedFiles)
    }

    @Test
    fun `persisted tracked keys survive wrapper recreation and do not retain substring matches`() = runTest {
        val delegate = realCache()
        val disk = track(delegate)
        val url = "https://cover.example/a"
        val retained = "nowplaying-cover|$url|1|2|3"
        val other = "nowplaying-cover|${url}b|1|2|3"
        write(disk, retained)
        write(disk, other)
        write(disk, "unrelated:$url")
        val reopened = track(delegate)

        val result = clearRetainingImageCache(loader(reopened), setOf(url))

        assertTrue(result.success)
        assertEntry(reopened, retained)
        assertNull(reopened.openSnapshot(other))
        assertNull(reopened.openSnapshot("unrelated:$url"))
    }

    @Test
    fun `an unwrapped legacy cache is conservatively retained`() = runTest {
        val disk = realCache()
        write(disk, "https://cover.example/a")
        write(disk, "https://cover.example/b")

        val result = clearRetainingImageCache(loader(disk), setOf("https://cover.example/a"))

        assertTrue(result.success)
        assertEquals(0L, result.freedBytes)
        assertEntry(disk, "https://cover.example/a")
        assertEntry(disk, "https://cover.example/b")
    }

    @Test
    fun `an empty retention set clears tracked and legacy entries and reports actual byte difference`() = runTest {
        val delegate = realCache()
        write(delegate, "legacy")
        val disk = track(delegate)
        write(disk, "tracked")
        val before = physicalBytes(disk)

        val result = clearRetainingImageCache(loader(disk), emptySet())

        assertTrue(result.success)
        assertNull(disk.openSnapshot("legacy"))
        assertNull(disk.openSnapshot("tracked"))
        assertEquals((before - physicalBytes(disk)).coerceAtLeast(0), result.freedBytes)
    }

    @Test
    fun `positive retention without cover URLs preserves unknown legacy entries`() = runTest {
        val delegate = realCache()
        write(delegate, "legacy")
        val disk = track(delegate)
        write(disk, "tracked")

        val result = clearRetainingImageCache(loader(disk), emptySet(), preserveUnmapped = true)

        assertTrue(result.success)
        assertEntry(disk, "legacy")
        assertNull(disk.openSnapshot("tracked"))
    }

    @Test
    fun `a nonretained entry being edited is left intact and reported partial`() = runTest {
        val disk = track(realCache())
        write(disk, "busy")
        write(disk, "other")
        val editor = requireNotNull(disk.openEditor("busy"))
        try {
            val result = clearRetainingImageCache(loader(disk), setOf("retained"))
            assertFalse(result.success)
            assertNull(disk.openSnapshot("other"))
        } finally {
            editor.abort()
        }
        assertEntry(disk, "busy")
    }

    @Test
    fun `an in flight edit of a retained key is never removed`() = runTest {
        val disk = track(realCache())
        write(disk, "retained")
        write(disk, "other")
        val editor = requireNotNull(disk.openEditor("retained"))
        try {
            val result = clearRetainingImageCache(loader(disk), setOf("retained"))

            assertTrue(result.success)
            assertNull(disk.openSnapshot("other"))
        } finally {
            editor.abort()
        }
        assertEntry(disk, "retained")
    }

    @Test
    fun `failed removal reports partial without preventing other entries from being cleared`() = runTest {
        val delegate = realCache()
        val failing = object : DiskCache by delegate {
            override fun remove(key: String): Boolean {
                if (key == "blocked") throw java.io.IOException("entry cannot be removed")
                return delegate.remove(key)
            }
        }
        val disk = track(failing)
        listOf("retained", "blocked", "other").forEach { write(disk, it) }
        val before = physicalBytes(disk)

        val result = clearRetainingImageCache(loader(disk), setOf("retained"))

        assertFalse(result.success)
        assertEntry(disk, "retained")
        assertEntry(disk, "blocked")
        assertNull(disk.openSnapshot("other"))
        assertEquals((before - physicalBytes(disk)).coerceAtLeast(0), result.freedBytes)
    }

    @Test
    fun `a refused removal of an existing entry is partial while an evicted index is harmless`() = runTest {
        val delegate = realCache()
        val disk = track(object : DiskCache by delegate {
            override fun remove(key: String): Boolean = if (key == "blocked") false else delegate.remove(key)
        })
        listOf("retained", "blocked", "evicted").forEach { write(disk, it) }
        assertTrue(delegate.remove("evicted"))

        val partial = clearRetainingImageCache(loader(disk), setOf("retained"))
        assertFalse(partial.success)
        assertEntry(disk, "blocked")
        assertEntry(disk, "retained")

        assertTrue(delegate.remove("blocked"))
        val completed = clearRetainingImageCache(loader(disk), setOf("retained"))
        assertTrue(completed.success)
        assertEntry(disk, "retained")
    }

    @Test
    fun `refused entries remain indexed after reopening and are removed when a later retry succeeds`() = runTest {
        val delegate = realCache()
        var refuseRemoval = true
        val failing = object : DiskCache by delegate {
            override fun remove(key: String): Boolean =
                if (key == "blocked" && refuseRemoval) false else delegate.remove(key)
        }
        val disk = track(failing)
        listOf("retained", "blocked", "other").forEach { write(disk, it) }

        assertFalse(clearRetainingImageCache(loader(disk), setOf("retained")).success)
        assertEntry(delegate, "blocked")
        assertNull(delegate.openSnapshot("other"))

        val reopened = track(failing)
        assertFalse(clearRetainingImageCache(loader(reopened), setOf("retained")).success)
        assertEntry(delegate, "blocked")

        refuseRemoval = false
        val completed = clearRetainingImageCache(loader(track(failing)), setOf("retained"))
        assertTrue(completed.success)
        assertTrue(completed.freedBytes > 0)
        assertNull(delegate.openSnapshot("blocked"))
        assertEntry(delegate, "retained")
    }

    @Test
    fun `cancellation during removal never selects retained or legacy entries`() = runTest {
        val delegate = realCache()
        write(delegate, "legacy")
        val disk = track(object : DiskCache by delegate {
            override fun remove(key: String): Boolean {
                throw CancellationException("cancel removal")
            }
        })
        write(disk, "retained")
        write(disk, "other")

        val operation = async { clearRetainingImageCache(loader(disk), setOf("retained")) }
        try {
            operation.await()
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            assertEntry(disk, "retained")
            assertEntry(disk, "legacy")
        }
    }

    private fun track(delegate: DiskCache): RetainingImageDiskCache =
        RetainingImageDiskCache(delegate, File(temporary.root, "image-cache-keys"))

    private fun realCache(): DiskCache = DiskCache.Builder()
        .directory(temporary.newFolder())
        .maxSizeBytes(1024 * 1024)
        .build()

    private fun loader(disk: DiskCache): ImageLoader = mock(ImageLoader::class.java).also {
        `when`(it.diskCache).thenReturn(disk)
    }

    private fun write(disk: DiskCache, key: String) {
        val editor = requireNotNull(disk.openEditor(key))
        disk.fileSystem.sink(editor.metadata).buffer().use { it.writeUtf8("metadata:$key") }
        disk.fileSystem.sink(editor.data).buffer().use { it.writeUtf8("data:$key") }
        editor.commit()
    }

    private fun assertEntry(disk: DiskCache, key: String) {
        val snapshot = disk.openSnapshot(key)
        assertNotNull(snapshot)
        requireNotNull(snapshot).use {
            assertEquals("metadata:$key", disk.fileSystem.source(it.metadata).buffer().use { source -> source.readUtf8() })
            assertEquals("data:$key", disk.fileSystem.source(it.data).buffer().use { source -> source.readUtf8() })
        }
    }

    private fun physicalBytes(disk: DiskCache): Long = disk.directory.toFile()
        .walkTopDown().filter(File::isFile).sumOf(File::length)
}
