package moe.ouom.neriplayer.common.storage

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

class AccountPreferencesLazyFallbackTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed primary opening and abandoned editor leave no account artifacts`() {
        val context = context()
        val preferences = failedPrimary(context)

        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(preferences))
        preferences.edit().putString("cookie", "cancelled")

        assertFalse(storage(context).hasArtifacts())
        assertTrue(accountDirectory(context).listFiles().orEmpty().isEmpty())
        assertNull(AccountPreferencesOpener.existing(context, "account"))
    }

    @Test
    fun `recovered primary is reopened after transient keystore failure without compatibility commit`() {
        val context = context()
        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(failedPrimary(context)))
        val primary = mock(SharedPreferences::class.java)

        val recovered = AccountPreferencesOpener.open(
            context, "account", { primary }, { error("primary must be preserved") }
        )

        assertSame(primary, recovered)
        assertFalse(storage(context).hasArtifacts())
        assertNull(AccountPreferencesOpener.existing(context, "account"))
    }

    @Test
    fun `unused fallback instance is shared without claiming an existing account`() {
        val context = context()
        val first = AccountPreferencesOpener.fallback(context, "account")

        assertSame(first, AccountPreferencesOpener.fallback(context, "account"))
        assertNull(AccountPreferencesOpener.existing(context, "account"))
        assertFalse(storage(context).hasArtifacts())
        assertTrue(first.getBoolean(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING, false))
    }

    @Test
    fun `first save publishes compatibility credentials ahead of recovered primary`() {
        val context = context()
        val preferences = failedPrimary(context)
        assertTrue(preferences.edit().putString("cookie", "saved")
            .remove(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING).commit())

        assertTrue(storage(context).hasArtifacts())
        assertEquals("saved", storage(context).open().getString("cookie", null))
        assertSame(preferences, AccountPreferencesOpener.open(
            context, "account", { error("saved compatibility credentials must win") }, { error("delete") }
        ))
    }

    @Test
    fun `first save publishes its payload without an intermediate empty account file`() {
        val context = context()
        val preferences = failedPrimary(context)
        val destination = storage(context).dataFile.toPath()
        var publications = 0
        mockStatic(Files::class.java) { invocation ->
            if (invocation.method.name == "move" && invocation.arguments[1] == destination) publications++
            invocation.callRealMethod()
        }.use {
            assertTrue(preferences.edit().putString("cookie", "saved")
                .remove(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING).commit())
        }

        assertEquals(1, publications)
        assertEquals("saved", storage(context).open().getString("cookie", null))
    }

    @Test
    fun `first clear publishes a logout tombstone ahead of recovered primary`() {
        val context = context()
        val preferences = failedPrimary(context)
        assertTrue(preferences.edit().clear().commit())

        assertTrue(storage(context).hasArtifacts())
        assertTrue(storage(context).open().all.isEmpty())
        assertSame(preferences, AccountPreferencesOpener.open(
            context, "account", { error("old primary credentials must not revive") }, { error("delete") }
        ))
    }

    @Test
    fun `failed first save keeps memory unchanged and can retry the same editor`() {
        val context = context()
        val preferences = failedPrimary(context)
        val changed = mutableListOf<String?>()
        preferences.registerOnSharedPreferenceChangeListener { _, key -> changed += key }
        val editor = preferences.edit().putString("cookie", "saved")
            .remove(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING)
        failDataPublication(context) {
            assertFalse(editor.commit())
        }
        assertNull(preferences.getString("cookie", null))
        assertTrue(preferences.getBoolean(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING, false))
        assertTrue(changed.isEmpty())

        assertTrue(editor.commit())

        assertEquals("saved", storage(context).open().getString("cookie", null))
    }

    @Test
    fun `failed first publication does not hide a recovered primary in the same process`() {
        val context = context()
        val preferences = failedPrimary(context)
        failDataPublication(context) {
            assertFalse(preferences.edit().putString("cookie", "unpublished").commit())
        }
        val staged = accountDirectory(context).listFiles()!!.associate { it.name to it.readBytes().toList() }
        val primary = mock(SharedPreferences::class.java)

        val recovered = AccountPreferencesOpener.open(context, "account", { primary }, { error("delete") })

        assertSame(primary, recovered)
        assertFalse(storage(context).dataFile.exists())
        assertEquals(staged, accountDirectory(context).listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test
    fun `unpublished initialization from another process does not hide a recovered primary`() {
        val original = context()
        val preferences = failedPrimary(original)
        failDataPublication(original) {
            assertFalse(preferences.edit().putString("cookie", "unpublished").commit())
        }
        val restored = context()
        assertTrue(accountDirectory(restored).mkdirs())
        accountDirectory(original).listFiles()!!.forEach { it.copyTo(File(accountDirectory(restored), it.name)) }
        val staged = accountDirectory(restored).listFiles()!!.associate { it.name to it.readBytes().toList() }
        val primary = mock(SharedPreferences::class.java)

        val recovered = AccountPreferencesOpener.open(restored, "account", { primary }, { error("delete") })

        assertSame(primary, recovered)
        assertFalse(storage(restored).dataFile.exists())
        assertEquals(staged, accountDirectory(restored).listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test
    fun `changed staged key without published data remains rejected and preserved`() {
        val context = context()
        val preferences = failedPrimary(context)
        failDataPublication(context) {
            assertFalse(preferences.edit().putString("cookie", "unpublished").commit())
        }
        val storage = storage(context)
        val marker = File(accountDirectory(context), "${storage.keyFile.nameWithoutExtension}.initializing")
        val key = storage.keyFile.readBytes().toList()
        val altered = marker.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        marker.writeBytes(altered)

        val reopened = AccountPreferencesOpener.open(
            context, "account", { error("damaged initialization must not expose old primary credentials") }, { error("delete") }
        )

        assertFalse(SecurePreferencesOpener.isPersistent(reopened))
        assertEquals(key, storage.keyFile.readBytes().toList())
        assertEquals(altered.toList(), marker.readBytes().toList())
        assertFalse(storage.dataFile.exists())
    }

    private fun failDataPublication(context: Context, action: () -> Unit) {
        val destination = storage(context).dataFile.toPath()
        mockStatic(Files::class.java) { invocation ->
            if (invocation.method.name == "move" && invocation.arguments[1] == destination) {
                throw IOException("initial account data publication failed")
            }
            invocation.callRealMethod()
        }.use { action() }
    }

    private fun failedPrimary(context: Context) = AccountPreferencesOpener.open(
        context, "account", { throw KeyStoreException("temporarily unavailable") }, { error("delete") }
    )

    private fun accountDirectory(context: Context) = File(context.noBackupFilesDir, "account_preferences")

    private fun storage(context: Context) = CompatibilityPreferencesFile(accountDirectory(context), "account")

    private fun context(): Context = mock(Context::class.java).also {
        `when`(it.noBackupFilesDir).thenReturn(temporaryFolder.newFolder())
    }
}
