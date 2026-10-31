package moe.ouom.neriplayer.data.sync.store.secure

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.util.UUID
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.github.syncDeletionDirectory
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverableSyncPreferencesTest {
    @Test
    fun `listeners follow the recovered storage once and unregister from both sessions`() {
        var interrupted = false
        val firstValues = VolatileSharedPreferences.shared("listener-first-${UUID.randomUUID()}")
        val first = object : SharedPreferences by firstValues {
            override fun getString(key: String?, defValue: String?): String? {
                if (interrupted) throw KeyStoreException("interrupted")
                return firstValues.getString(key, defValue)
            }
        }
        val second = object : SharedPreferences by VolatileSharedPreferences.shared("listener-second-${UUID.randomUUID()}") {}
        val preferences = RecoverableSyncPreferences { if (interrupted) second else first }
        val changes = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes += key }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        assertTrue(preferences.isPersistent)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        preferences.edit().putString("first", "value").commit()
        assertEquals(listOf("first"), changes)

        interrupted = true
        assertThrows(SyncStorageUnavailableException::class.java) { preferences.getString("first", null) }
        assertTrue(preferences.isPersistent)
        changes.clear()
        preferences.edit().putString("second", "value").commit()
        firstValues.edit().putString("stale", "value").commit()
        assertEquals(listOf("second"), changes)

        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        preferences.edit().putString("removed", "value").commit()
        assertEquals(listOf("second"), changes)
    }

    @Test
    fun `unavailable keystore cannot masquerade as empty configuration or accept writes`() {
        var available = false
        val disk = MemorySyncPreferences(mapOf("github_token" to "saved-token", "device_id" to "saved-device"))
        val preferences = RecoverableSyncPreferences {
            if (!available) throw KeyStoreException("temporarily unavailable")
            disk.preferences
        }

        assertFalse(preferences.isPersistent)
        assertThrows(SyncStorageUnavailableException::class.java) { preferences.getString("github_token", null) }
        assertThrows(SyncStorageUnavailableException::class.java) { preferences.edit().clear().commit() }
        assertEquals("saved-token", disk.durableValues["github_token"])

        available = true
        assertTrue(preferences.isPersistent)
        assertEquals("saved-token", preferences.getString("github_token", null))
        assertEquals("saved-device", preferences.getString("device_id", null))
    }

    @Test
    fun `existing GitHub and WebDAV stores recover through the same handle after a read fails`() {
        var available = true
        val disk = MemorySyncPreferences(mapOf("github_token" to "saved-token", "device_id" to "saved-device"))
        val initial = object : SharedPreferences by disk.preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (!available) throw KeyStoreException("key service interrupted")
                return disk.preferences.getString(key, defValue)
            }
        }
        var opens = 0
        val preferences = RecoverableSyncPreferences {
            if (!available) throw KeyStoreException("temporarily unavailable")
            if (opens++ == 0) initial else disk.preferences
        }
        val first = SecureTokenStorage(preferences)
        val second = SecureTokenStorage(preferences)
        val webDav = WebDavStorage(preferences)

        available = false
        assertThrows(SyncStorageUnavailableException::class.java) { first.getToken() }
        assertFalse(second.isPersistent)
        assertFalse(webDav.isPersistent)

        available = true
        assertTrue(first.isPersistent)
        second.saveToken("replacement-token")
        assertEquals("replacement-token", first.getToken())
        assertEquals("replacement-token", disk.restart().values["github_token"])
        assertEquals("saved-device", first.getDeviceId())
        assertTrue(webDav.isPersistent)
    }

    @Test
    fun `an editor captured before recovery cannot commit into the old session`() {
        val old = MemorySyncPreferences(mapOf("github_token" to "old-token"))
        val recovered = MemorySyncPreferences(mapOf("github_token" to "recovered-token"))
        var interrupted = false
        val initial = object : SharedPreferences by old.preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (interrupted) throw KeyStoreException("interrupted")
                return old.preferences.getString(key, defValue)
            }
        }
        val preferences = RecoverableSyncPreferences { if (interrupted) recovered.preferences else initial }
        val editor = preferences.edit().putString("github_token", "late-token")
        interrupted = true
        assertThrows(SyncStorageUnavailableException::class.java) { preferences.getString("github_token", null) }
        assertEquals("recovered-token", preferences.getString("github_token", null))

        assertThrows(SyncStorageUnavailableException::class.java) { editor.commit() }
        assertEquals("recovered-token", preferences.getString("github_token", null))
        assertEquals("old-token", old.durableValues["github_token"])
        assertEquals("recovered-token", recovered.durableValues["github_token"])
    }

    @Test
    fun `rejecting stale editors preserves recovered sessions and metadata guards`() {
        var available = true
        val disk = MemorySyncPreferences(mapOf(
            "github_token" to "saved-token", "sync_mutation_version" to 7L, "sync_configuration_generation" to 3L
        ))
        val primary = object : SharedPreferences by disk.preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (!available) throw KeyStoreException("interrupted")
                return disk.preferences.getString(key, defValue)
            }
        }
        var opens = 0
        val preferences = RecoverableSyncPreferences {
            if (!available) throw KeyStoreException("temporarily unavailable")
            opens++
            primary
        }
        val storage = SecureTokenStorage(preferences)
        val stale = preferences.edit().putString("github_token", "stale-token")
        available = false
        assertThrows(SyncStorageUnavailableException::class.java) { storage.getToken() }
        available = true
        val guard = storage.captureSyncMetadataGuard()
        val healthy = preferences.edit().putString("healthy", "saved")

        repeat(2) {
            assertThrows(SyncStorageUnavailableException::class.java) { stale.putString("github_token", "late-token") }
            assertThrows(SyncStorageUnavailableException::class.java) { stale.commit() }
            assertThrows(SyncStorageUnavailableException::class.java) { stale.apply() }
            assertEquals(8L, storage.getSyncMutationVersion())
            assertEquals(4L, disk.values["sync_configuration_generation"])
            assertTrue(guard { storage.saveLastCompletedSyncTime(100L) })
        }

        assertTrue(healthy.commit())
        assertEquals(2, opens)
        assertEquals("saved-token", disk.restart().values["github_token"])
        assertEquals("saved", disk.restart().values["healthy"])
    }

    @Test
    fun `reopening invalidates snapshots and completion guards captured before the interruption`() {
        var available = true
        val disk = MemorySyncPreferences(mapOf("sync_mutation_version" to 7L, "sync_configuration_generation" to 3L))
        val primary = object : SharedPreferences by disk.preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (!available) throw KeyStoreException("interrupted")
                return disk.preferences.getString(key, defValue)
            }
        }
        val preferences = RecoverableSyncPreferences {
            if (!available) throw KeyStoreException("temporarily unavailable")
            primary
        }
        val storage = SecureTokenStorage(preferences)
        val guard = storage.captureSyncMetadataGuard()
        assertEquals(7L, storage.getSyncMutationVersion())
        available = false
        assertThrows(SyncStorageUnavailableException::class.java) { storage.getToken() }
        available = true

        assertEquals(8L, storage.getSyncMutationVersion())
        assertFalse(guard { throw AssertionError("a stale session cannot publish completion") })
        assertEquals(4L, disk.restart().values["sync_configuration_generation"])
    }

    @Test
    fun `recovery never redirects or cleans the persistent deletion directory while unavailable`() {
        val root = Files.createTempDirectory("sync-keystore-recovery").toFile()
        try {
            val persistent = File(root, "no_backup")
            val directory = File(persistent, "sync-deletions").apply { mkdirs() }
            val generation = File(directory, "12345678-1234-1234-1234-123456789abc.json").apply { writeText("retained") }
            val preferences = RecoverableSyncPreferences { throw KeyStoreException("unavailable") }

            assertEquals(directory, syncDeletionDirectory(preferences, persistent, File(root, "cache")))
            assertThrows(SyncStorageUnavailableException::class.java) {
                SecureTokenStorage(preferences, directory).getLyricOverrides()
            }
            assertEquals("retained", generation.readText())
            assertEquals(listOf(generation), directory.listFiles()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an interrupted deletion update preserves referenced generations and resumes with the original state`() {
        val root = Files.createTempDirectory("sync-deletion-recovery").toFile()
        try {
            var available = true
            val disk = MemorySyncPreferences()
            val primary = object : SharedPreferences by disk.preferences {
                override fun getString(key: String?, defValue: String?): String? {
                    if (!available) throw KeyStoreException("interrupted")
                    return disk.preferences.getString(key, defValue)
                }
            }
            val preferences = RecoverableSyncPreferences {
                if (!available) throw KeyStoreException("temporarily unavailable")
                primary
            }
            val directory = File(root, "sync-deletions")
            val storage = SecureTokenStorage(preferences, directory)
            storage.addDeletedPlaylistId(42L)
            val generations = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
            assertTrue(generations.isNotEmpty())

            available = false
            assertThrows(SyncStorageUnavailableException::class.java) { storage.addDeletedPlaylistId(43L) }
            assertEquals(generations, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })

            available = true
            assertEquals(setOf(42L), storage.getDeletedPlaylistIds())
            storage.addDeletedPlaylistId(43L)
            assertEquals(setOf(42L, 43L), SecureTokenStorage(disk.restart().preferences, directory).getDeletedPlaylistIds())
        } finally {
            root.deleteRecursively()
        }
    }
}
