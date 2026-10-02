package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SyncBatchClassificationTest {
    @Test
    fun `a sync batch resolves local album names once while retaining all local filters`() {
        var resolutions = 0
        val host = AndroidSyncSanitizationHost(mock(Context::class.java)) {
            resolutions++
            setOf("本地文件", "Local Files", "Localized local files")
        }
        repeat(2_000) {
            assertFalse(host.isLocalSong("netease", null, 0))
            assertFalse(host.isLocalSong("LOCAL FILES", null, 42))
            assertFalse(host.isLocalSong("Local Files", "https://example.com/song", 0))
            assertTrue(host.isLocalSong("localized LOCAL FILES", null, 0))
            assertTrue(host.isLocalSong(LocalSongSupport.LOCAL_ALBUM_IDENTITY, null, 0))
            assertTrue(host.isLocalSong("netease", "content://local/123", 42))
            assertTrue(host.isLocalSong("netease", "file:///music.flac", 42))
        }
        assertEquals(1, resolutions)
    }

    @Test
    fun `a new batch captures new localized names without changing the active batch`() {
        var names = setOf("first language")
        val context = mock(Context::class.java)
        val first = AndroidSyncSanitizationHost(context) { names }
        assertTrue(first.isLocalSong("first language", null, 0))
        names = setOf("second language")
        assertTrue(first.isLocalSong("first language", null, 0))
        assertFalse(first.isLocalSong("second language", null, 0))
        val second = AndroidSyncSanitizationHost(context) { names }
        assertTrue(second.isLocalSong("second language", null, 0))
        assertFalse(second.isLocalSong("first language", null, 0))
    }
}
