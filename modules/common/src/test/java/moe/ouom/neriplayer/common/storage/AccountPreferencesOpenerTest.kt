package moe.ouom.neriplayer.common.storage

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class AccountPreferencesOpenerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `healthy keystore opens without compatibility files`() {
        val context = context()
        val healthy = mock(SharedPreferences::class.java)
        val opened = AccountPreferencesOpener.open(context, "account", { healthy }, { error("delete") })
        assertSame(healthy, opened)
        assertFalse(AccountPreferencesOpener.isCompatibilityStorage(opened))
        assertNull(AccountPreferencesOpener.existing(context, "account"))
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `keystore failure persists credentials across independent instances without plaintext`() {
        val context = context()
        val preferences = fallback(context)
        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(preferences))
        assertTrue(preferences.edit().putString("cookie", "private-session-token").commit())
        val reopened = file(context).open()
        assertEquals("private-session-token", reopened.getString("cookie", null))
        context.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach { stored ->
            val bytes = stored.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("private-session-token"))
            assertFalse(bytes.contains("cookie"))
        }
    }

    @Test
    fun `any primary failure preserves original file and falls back`() {
        val context = context()
        val original = File(temporaryFolder.root, "encrypted.xml").apply { writeText("original ciphertext") }
        val opened = AccountPreferencesOpener.open(
            context, "account", { throw IOException("corrupt keyset") }, { original.delete() }
        )
        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(opened))
        assertEquals("original ciphertext", original.readText())
    }

    @Test
    fun `logout tombstone wins over recovered keystore on reopen`() {
        val context = context()
        val persisted = file(context).open()
        assertTrue(persisted.edit().putString("cookie", "old").commit())
        assertTrue(persisted.edit().clear().commit())
        val reopened = AccountPreferencesOpener.open(
            context, "account", { error("old keystore credentials must not revive") }, { error("delete") }
        )
        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(reopened))
        assertFalse(reopened.contains("cookie"))
        assertNotNull(AccountPreferencesOpener.existing(context, "account"))
    }

    @Test
    fun `tampered ciphertext is rejected without overwriting either file`() {
        val context = context()
        val storage = file(context)
        assertTrue(storage.open().edit().putString("cookie", "secret").commit())
        val altered = storage.dataFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        storage.dataFile.writeBytes(altered)
        val originalKey = storage.keyFile.readBytes()
        assertThrows(Exception::class.java) { storage.open() }
        val degraded = AccountPreferencesOpener.existing(context, "account")!!
        assertFalse(SecurePreferencesOpener.isPersistent(degraded))
        assertArrayEquals(altered, storage.dataFile.readBytes())
        assertArrayEquals(originalKey, storage.keyFile.readBytes())
    }

    @Test
    fun `wrong key and missing key cannot erase encrypted account`() {
        val context = context()
        val storage = file(context)
        assertTrue(storage.open().edit().putString("cookie", "secret").commit())
        val encrypted = storage.dataFile.readBytes()
        storage.keyFile.writeBytes(ByteArray(32) { 7 })
        assertThrows(Exception::class.java) { storage.open() }
        assertArrayEquals(encrypted, storage.dataFile.readBytes())
        assertTrue(storage.keyFile.delete())
        assertThrows(Exception::class.java) { storage.open() }
        assertFalse(storage.keyFile.exists())
        assertArrayEquals(encrypted, storage.dataFile.readBytes())
    }

    @Test
    fun `active writer refuses changed key or damaged ciphertext`() {
        val storage = file(context())
        val preferences = storage.open()
        assertTrue(preferences.edit().putString("cookie", "saved").commit())
        val originalKey = storage.keyFile.readBytes()
        val originalData = storage.dataFile.readBytes()
        val changedKey = ByteArray(32) { 7 }
        storage.keyFile.writeBytes(changedKey)
        assertFalse(preferences.edit().putString("cookie", "lost").commit())
        assertArrayEquals(originalData, storage.dataFile.readBytes())
        assertArrayEquals(changedKey, storage.keyFile.readBytes())
        storage.keyFile.writeBytes(originalKey)
        val damaged = originalData.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        storage.dataFile.writeBytes(damaged)
        assertFalse(preferences.edit().clear().commit())
        assertEquals("saved", preferences.getString("cookie", null))
        assertArrayEquals(damaged, storage.dataFile.readBytes())
        assertTrue(storage.keyFile.delete())
        assertFalse(preferences.edit().clear().commit())
        assertFalse(storage.keyFile.exists())
    }

    @Test
    fun `failed commit keeps previous state and does not notify listeners`() {
        val context = context()
        val storage = file(context)
        val preferences = storage.open()
        assertTrue(preferences.edit().putString("cookie", "saved").commit())
        val changed = mutableListOf<String?>()
        preferences.registerOnSharedPreferenceChangeListener { _, key -> changed += key }
        assertTrue(storage.dataFile.delete())
        assertTrue(storage.dataFile.mkdir())
        assertTrue(File(storage.dataFile, "blocker").createNewFile())
        assertFalse(preferences.edit().putString("cookie", "lost").commit())
        assertEquals("saved", preferences.getString("cookie", null))
        assertTrue(changed.isEmpty())
        assertThrows(IllegalStateException::class.java) { preferences.edit().putString("cookie", "lost").apply() }
    }

    @Test
    fun `unwritable fallback initialization stays volatile without touching primary`() {
        val context = context()
        File(context.noBackupFilesDir, "account_preferences").writeText("blocked")
        val opened = fallback(context)
        assertFalse(SecurePreferencesOpener.isPersistent(opened))
        assertFalse(AccountPreferencesOpener.isCompatibilityStorage(opened))
    }

    @Test
    fun `same account shares concurrent readers writers and retained sets`() {
        val context = context()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val opened = executor.invokeAll((0 until 20).map { index -> Callable {
                val preferences = AccountPreferencesOpener.fallback(context, "account")
                check(preferences.edit().putInt("key-$index", index).commit())
                preferences
            } }).map { it.get() }
            opened.forEach { assertSame(opened.first(), it) }
            val persisted = file(context).open()
            assertEquals((0 until 20).map { "key-$it" }.toSet(),
                persisted.all.keys - AccountPreferencesOpener.LEGACY_MIGRATION_PENDING)
            assertTrue(persisted.getBoolean(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING, false))
            val source = mutableSetOf("one")
            assertTrue(opened.first().edit().putStringSet("set", source).commit())
            source += "two"
            opened.first().getStringSet("set", null)!!.add("three")
            assertEquals(setOf("one"), file(context).open().getStringSet("set", null))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `all preference types survive disk reopen`() {
        val storage = file(context())
        assertTrue(storage.open().edit().putString(null, "nullable key")
            .putInt("int", 7).putLong("long", 8L).putFloat("float", 1.5f)
            .putBoolean("bool", true).putStringSet("set", mutableSetOf("a", "b")).commit())
        val reopened = storage.open()
        assertEquals("nullable key", reopened.getString(null, null))
        assertEquals(7, reopened.getInt("int", 0))
        assertEquals(8L, reopened.getLong("long", 0))
        assertEquals(1.5f, reopened.getFloat("float", 0f))
        assertTrue(reopened.getBoolean("bool", false))
        assertEquals(setOf("a", "b"), reopened.getStringSet("set", null))
    }

    private fun fallback(context: Context): SharedPreferences = AccountPreferencesOpener.open(
        context, "account", { throw KeyStoreException("unusable") }, { error("delete") }
    )

    private fun context(): Context = mock(Context::class.java).also {
        `when`(it.noBackupFilesDir).thenReturn(temporaryFolder.newFolder())
    }

    private fun file(context: Context) = CompatibilityPreferencesFile(
        File(context.noBackupFilesDir, "account_preferences"), "account"
    )
}
