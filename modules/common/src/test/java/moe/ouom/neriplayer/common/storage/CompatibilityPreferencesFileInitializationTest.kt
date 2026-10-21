package moe.ouom.neriplayer.common.storage

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

class CompatibilityPreferencesFileInitializationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed initial data publication can retry without replacing the key`() {
        val storage = storage(context())
        failDataPublication(storage) {
            assertThrows(IOException::class.java) { storage.open() }
        }
        assertTrue(storage.keyFile.isFile)
        assertFalse(storage.dataFile.exists())
        val originalKey = storage.keyFile.readBytes()

        val reopened = storage.open()

        assertTrue(SecurePreferencesOpener.isPersistent(reopened))
        assertTrue(reopened.getBoolean(MIGRATION_PENDING, false))
        assertTrue(reopened.edit().putString("cookie", "saved").commit())
        assertArrayEquals(originalKey, storage.keyFile.readBytes())
        assertEquals("saved", storage.open().getString("cookie", null))
        assertFalse(initializationFile(storage).exists())
    }

    @Test
    fun `unsupported atomic marker or key publication leaves only complete initialization artifacts`() {
        for (publishKey in listOf(false, true)) {
            val storage = storage(context())
            val destination = if (publishKey) storage.keyFile else initializationFile(storage)
            withoutAtomicPublication(destination) {
                assertThrows(AtomicMoveNotSupportedException::class.java) { storage.open() }
            }
            assertFalse(storage.keyFile.exists())
            assertFalse(storage.dataFile.exists())
            if (publishKey) {
                assertEquals(32L, initializationFile(storage).length())
                assertEquals(listOf(initializationFile(storage)), storage.keyFile.parentFile!!.listFiles()!!.toList())
            } else {
                assertFalse(storage.hasArtifacts())
                assertTrue(storage.keyFile.parentFile!!.listFiles()!!.isEmpty())
            }

            assertTrue(storage.open().getBoolean(MIGRATION_PENDING, false))
            assertFalse(initializationFile(storage).exists())
        }
    }

    @Test
    fun `unsupported atomic initial data publication remains safely retryable`() {
        val storage = storage(context())
        withoutAtomicPublication(storage.dataFile) {
            assertThrows(AtomicMoveNotSupportedException::class.java) { storage.open() }
        }
        assertFalse(storage.dataFile.exists())
        val key = storage.keyFile.readBytes()
        assertArrayEquals(key, initializationFile(storage).readBytes())

        assertTrue(storage.open().getBoolean(MIGRATION_PENDING, false))

        assertArrayEquals(key, storage.keyFile.readBytes())
        assertFalse(initializationFile(storage).exists())
    }

    @Test
    fun `failed fallback writability probe recovers from its cached volatile instance`() {
        val context = context()
        val storage = storage(context)
        val blockedDirectory = storage.keyFile.parentFile!!
        blockedDirectory.writeText("blocked")
        assertFalse(SecurePreferencesOpener.isPersistent(AccountPreferencesOpener.fallback(context, "account")))
        assertTrue(blockedDirectory.delete())

        val recovered = AccountPreferencesOpener.fallback(context, "account")

        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(recovered))
        assertFalse(storage.hasArtifacts())
        assertTrue(recovered.edit().putString("cookie", "saved").commit())
        assertEquals("saved", storage.open().getString("cookie", null))
    }

    @Test
    fun `key-only initialization left by a terminated process resumes with the staged key`() {
        val storage = storage(context())
        val key = ByteArray(32) { 3 }
        stageInitialization(storage, key)
        storage.keyFile.writeBytes(key)

        val reopened = storage.open()

        assertTrue(reopened.getBoolean(MIGRATION_PENDING, false))
        assertTrue(reopened.edit().putString("cookie", "saved").commit())
        assertArrayEquals(key, storage.keyFile.readBytes())
        assertFalse(initializationFile(storage).exists())
    }

    @Test
    fun `initialization interrupted before key publication uses its staged key`() {
        val storage = storage(context())
        val key = ByteArray(32) { 5 }
        stageInitialization(storage, key)

        assertTrue(storage.open().getBoolean(MIGRATION_PENDING, false))

        assertArrayEquals(key, storage.keyFile.readBytes())
        assertFalse(initializationFile(storage).exists())
    }

    @Test
    fun `published initialization data finalizes before account preferences are exposed`() {
        val storage = storage(context())
        assertTrue(storage.open().edit().putBoolean(MIGRATION_PENDING, true).commit())
        stageInitialization(storage, storage.keyFile.readBytes())
        val encrypted = storage.dataFile.readBytes()

        val reopened = storage.open()

        assertTrue(reopened.getBoolean(MIGRATION_PENDING, false))
        assertArrayEquals(encrypted, storage.dataFile.readBytes())
        assertFalse(initializationFile(storage).exists())
    }

    @Test
    fun `unmarked key-only storage remains rejected and untouched`() {
        val storage = storage(context())
        storage.keyFile.parentFile!!.mkdirs()
        val key = ByteArray(32) { 7 }
        storage.keyFile.writeBytes(key)

        assertThrows(IOException::class.java) { storage.open() }

        assertArrayEquals(key, storage.keyFile.readBytes())
        assertFalse(storage.dataFile.exists())
        assertFalse(initializationFile(storage).exists())
    }

    @Test
    fun `initialization marker cannot replace a missing key beside existing ciphertext`() {
        val storage = storage(context())
        assertTrue(storage.open().edit().putString("cookie", "saved").commit())
        stageInitialization(storage, storage.keyFile.readBytes())
        val encrypted = storage.dataFile.readBytes()
        assertTrue(storage.keyFile.delete())

        assertThrows(IOException::class.java) { storage.open() }

        assertFalse(storage.keyFile.exists())
        assertArrayEquals(encrypted, storage.dataFile.readBytes())
        assertTrue(initializationFile(storage).exists())
    }

    @Test
    fun `initialization marker cannot hide damaged ciphertext`() {
        val storage = storage(context())
        assertTrue(storage.open().edit().putBoolean(MIGRATION_PENDING, true).commit())
        stageInitialization(storage, storage.keyFile.readBytes())
        val damaged = storage.dataFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        storage.dataFile.writeBytes(damaged)
        val key = storage.keyFile.readBytes()

        assertThrows(Exception::class.java) { storage.open() }

        assertArrayEquals(damaged, storage.dataFile.readBytes())
        assertArrayEquals(key, storage.keyFile.readBytes())
        assertTrue(initializationFile(storage).exists())
    }

    @Test
    fun `changed staged key cannot replace a published initialization key`() {
        val storage = storage(context())
        assertTrue(storage.open().edit().putBoolean(MIGRATION_PENDING, true).commit())
        val key = storage.keyFile.readBytes()
        val encrypted = storage.dataFile.readBytes()
        val staged = ByteArray(32) { 9 }
        stageInitialization(storage, staged)

        assertThrows(IOException::class.java) { storage.open() }

        assertArrayEquals(key, storage.keyFile.readBytes())
        assertArrayEquals(encrypted, storage.dataFile.readBytes())
        assertArrayEquals(staged, initializationFile(storage).readBytes())
    }

    @Test
    fun `published credentials finalize initialization without replacing their ciphertext or key`() {
        val storage = storage(context())
        assertTrue(storage.open().edit().putString("cookie", "saved").commit())
        val key = storage.keyFile.readBytes()
        val encrypted = storage.dataFile.readBytes()
        stageInitialization(storage, key)

        assertEquals("saved", storage.open().getString("cookie", null))

        assertArrayEquals(key, storage.keyFile.readBytes())
        assertArrayEquals(encrypted, storage.dataFile.readBytes())
        assertFalse(initializationFile(storage).exists())
    }

    private fun failDataPublication(storage: CompatibilityPreferencesFile, action: () -> Unit) {
        val destination = storage.dataFile.toPath()
        mockStatic(Files::class.java) { invocation ->
            if (invocation.method.name == "move" && invocation.arguments[1] == destination) {
                throw IOException("initial data publication failed")
            }
            invocation.callRealMethod()
        }.use { action() }
    }

    private fun withoutAtomicPublication(destination: File, action: () -> Unit) {
        val target = destination.toPath()
        mockStatic(Files::class.java) { invocation ->
            if (invocation.method.name == "move" && invocation.arguments[1] == target &&
                invocation.arguments.drop(2).any {
                    it == StandardCopyOption.ATOMIC_MOVE ||
                        it is Array<*> && it.contains(StandardCopyOption.ATOMIC_MOVE)
                }
            ) {
                throw AtomicMoveNotSupportedException("staged", "destination", "unsupported")
            }
            invocation.callRealMethod()
        }.use { action() }
    }

    private fun stageInitialization(storage: CompatibilityPreferencesFile, key: ByteArray) {
        storage.keyFile.parentFile!!.mkdirs()
        initializationFile(storage).writeBytes(key)
    }

    private fun initializationFile(storage: CompatibilityPreferencesFile) =
        File(storage.keyFile.parentFile, "${storage.keyFile.nameWithoutExtension}.initializing")

    private fun context(): Context = mock(Context::class.java).also {
        `when`(it.noBackupFilesDir).thenReturn(temporaryFolder.newFolder())
    }

    private fun storage(context: Context) = CompatibilityPreferencesFile(
        File(context.noBackupFilesDir, "account_preferences"), "account"
    )

    private companion object {
        const val MIGRATION_PENDING = AccountPreferencesOpener.LEGACY_MIGRATION_PENDING
    }
}
