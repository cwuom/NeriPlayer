package moe.ouom.neriplayer.data.sync.store.github

import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SecureTokenStorageDegradationTest {
    private val persistentRoot = File("/data/user/0/app/no_backup")
    private val cacheRoot = File("/data/user/0/app/cache")

    @Test
    fun `persistent preferences keep deletion generations in no backup storage`() {
        val directory = syncDeletionDirectory(mock(SharedPreferences::class.java), persistentRoot, cacheRoot)

        assertEquals(File(persistentRoot, "sync-deletions"), directory)
    }

    @Test
    fun `degraded preferences never point generation cleanup at persistent files`() {
        val degraded = VolatileSharedPreferences.shared("degraded-${UUID.randomUUID()}")

        val directory = syncDeletionDirectory(degraded, persistentRoot, cacheRoot)

        assertEquals(File(cacheRoot, "sync-deletions-volatile"), directory.parentFile)
        assertNotEquals(File(persistentRoot, "sync-deletions"), directory)
        assertEquals(directory, syncDeletionDirectory(degraded, persistentRoot, cacheRoot))
    }

    @Test
    fun `stores report whether sync metadata survives the process`() {
        val degraded = VolatileSharedPreferences.shared("degraded-${UUID.randomUUID()}")

        assertTrue(SecureTokenStorage(mock(SharedPreferences::class.java)).isPersistent)
        assertFalse(SecureTokenStorage(degraded).isPersistent)
        assertTrue(WebDavStorage(mock(SharedPreferences::class.java)).isPersistent)
        assertFalse(WebDavStorage(degraded).isPersistent)
    }
}
