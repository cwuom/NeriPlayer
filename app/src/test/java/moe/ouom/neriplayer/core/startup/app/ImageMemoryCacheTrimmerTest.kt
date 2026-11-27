package moe.ouom.neriplayer.core.startup.app

import android.app.Activity
import android.content.ComponentCallbacks2
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.memory.MemoryCache
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ImageMemoryCacheTrimmerTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun `visible ui levels keep the whole cache`() {
        assertNull(hiddenUiImageCacheTargetBytes(level = 0, maxSizeBytes = 400))
        assertNull(hiddenUiImageCacheTargetBytes(level = 15, maxSizeBytes = 400))
    }

    @Test
    fun `hidden ui and stronger levels shrink to a quarter`() {
        assertEquals(100, hiddenUiImageCacheTargetBytes(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, 400))
        assertEquals(100, hiddenUiImageCacheTargetBytes(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, 400))
    }

    @Test
    fun `disabled cache has no target`() {
        assertNull(hiddenUiImageCacheTargetBytes(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, 0))
    }

    @Test
    fun `trim removes least recently used entries first`() {
        val cache = threeEntryCache()

        val removed = cache.trimToBytes(100)

        assertEquals(2, removed)
        assertEquals(listOf("c"), cache.keys.map { it.key })
        assertEquals(100, cache.size)
    }

    @Test
    fun `trim is a no-op when already under target`() {
        val cache = FakeMemoryCache(maxSize = 400).apply { put("current", 80) }

        assertEquals(0, cache.trimToBytes(100))
        assertEquals(80, cache.size)
    }

    @Test
    fun `system trim callback reacts only when ui is hidden`() {
        val cache = threeEntryCache()
        val trimmer = ImageMemoryCacheTrimmer({ cache })

        trimmer.onTrimMemory(15)
        assertEquals(300, cache.size)

        trimmer.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(listOf("c"), cache.keys.map { it.key })
    }

    @Test
    fun `last stopped activity trims after the delay even without a system callback`() {
        val cache = threeEntryCache()
        val trimmer = ImageMemoryCacheTrimmer({ cache })
        trimmer.onActivityStarted(activity)

        trimmer.onActivityStopped(activity)
        idleFor(HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS - 1)
        assertEquals(300, cache.size)

        idleFor(1)
        assertEquals(listOf("c"), cache.keys.map { it.key })
    }

    @Test
    fun `returning before the delay keeps the cache`() {
        val cache = threeEntryCache()
        val trimmer = ImageMemoryCacheTrimmer({ cache })
        trimmer.onActivityStarted(activity)

        trimmer.onActivityStopped(activity)
        idleFor(HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS / 2)
        trimmer.onActivityStarted(activity)
        idleFor(HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS * 2)

        assertEquals(300, cache.size)
    }

    @Test
    fun `stopping one of two visible activities keeps the cache`() {
        val cache = threeEntryCache()
        val trimmer = ImageMemoryCacheTrimmer({ cache })
        trimmer.onActivityStarted(activity)
        trimmer.onActivityStarted(activity)

        trimmer.onActivityStopped(activity)
        idleFor(HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS * 2)

        assertEquals(300, cache.size)
    }

    @Test
    fun `trimmer tolerates missing cache`() {
        val trimmer = ImageMemoryCacheTrimmer({ null })
        trimmer.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        trimmer.onActivityStopped(activity)
        idleFor(HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS)
    }

    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun threeEntryCache() = FakeMemoryCache(maxSize = 400).apply {
        put("a", 100)
        put("b", 100)
        put("c", 100)
    }

    private class FakeMemoryCache(override val maxSize: Int) : MemoryCache {
        private val entries = LinkedHashMap<MemoryCache.Key, Int>()

        fun put(key: String, bytes: Int) {
            entries[MemoryCache.Key(key)] = bytes
        }

        override val size: Int get() = entries.values.sum()
        override val keys: Set<MemoryCache.Key> get() = LinkedHashSet(entries.keys)
        override fun get(key: MemoryCache.Key): MemoryCache.Value? = null
        override fun set(key: MemoryCache.Key, value: MemoryCache.Value) = Unit
        override fun remove(key: MemoryCache.Key): Boolean = entries.remove(key) != null
        override fun clear() = entries.clear()
        override fun trimMemory(level: Int) = Unit
    }
}
