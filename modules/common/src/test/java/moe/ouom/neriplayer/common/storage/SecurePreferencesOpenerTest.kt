package moe.ouom.neriplayer.common.storage

import android.content.SharedPreferences
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SecurePreferencesOpenerTest {
    private val events = mutableListOf<String>()
    private val report: (SecurePreferencesEvent, Throwable) -> Unit = { event, error ->
        events += "${event.name}:${error.message}"
    }

    @Test
    fun `healthy storage opens without delete`() {
        val preferences = mock(SharedPreferences::class.java)
        val opened = SecurePreferencesOpener.open(
            name = uniqueName(),
            create = { preferences },
            delete = { throw AssertionError("healthy storage must not be deleted") },
            report = report
        )
        assertSame(preferences, opened)
        assertTrue(SecurePreferencesOpener.isPersistent(opened))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unusable keystore master key keeps credentials and falls back to volatile storage`() {
        val name = uniqueName()
        var attempts = 0
        val keystoreFailure = KeyStoreException("the master key exists but is unusable")
        val opened = SecurePreferencesOpener.open(
            name = name,
            create = { attempts++; throw IllegalStateException("open", keystoreFailure) },
            delete = { throw AssertionError("deleting cannot repair an unusable master key") },
            report = report
        )
        assertEquals(1, attempts)
        assertFalse(SecurePreferencesOpener.isPersistent(opened))
        assertSame(VolatileSharedPreferences.existing(name), opened)
        assertEquals(listOf("OPEN_FAILED:open", "VOLATILE_FALLBACK:open"), events)
    }

    @Test
    fun `keystore failure never rethrows even when corruption must not be rebuilt`() {
        val opened = SecurePreferencesOpener.open(
            name = uniqueName(),
            create = { throw ProviderException("Keystore operation failed") },
            delete = { throw AssertionError("sync metadata must not be deleted") },
            rebuildCorrupted = false,
            report = report
        )
        assertFalse(SecurePreferencesOpener.isPersistent(opened))
    }

    @Test
    fun `corruption without rebuild permission reaches the caller unchanged`() {
        val corruption = GeneralSecurityException("keyset corrupted")
        val thrown = assertThrows(GeneralSecurityException::class.java) {
            SecurePreferencesOpener.open(
                name = uniqueName(),
                create = { throw corruption },
                delete = { throw AssertionError("sync metadata must not be deleted") },
                rebuildCorrupted = false,
                report = report
            )
        }
        assertSame(corruption, thrown)
        assertEquals(listOf("OPEN_FAILED:keyset corrupted"), events)
    }

    @Test
    fun `corrupted storage is deleted once and reopened`() {
        val preferences = mock(SharedPreferences::class.java)
        var attempts = 0
        val opened = SecurePreferencesOpener.open(
            name = uniqueName(),
            create = { if (attempts++ == 0) throw GeneralSecurityException("corrupt") else preferences },
            delete = { events += "delete" },
            report = report
        )
        assertSame(preferences, opened)
        assertEquals(listOf("OPEN_FAILED:corrupt", "delete"), events)
    }

    @Test
    fun `failed rebuild degrades instead of crashing the caller`() {
        var attempts = 0
        val opened = SecurePreferencesOpener.open(
            name = uniqueName(),
            create = { throw GeneralSecurityException(if (attempts++ == 0) "corrupt" else "still broken") },
            delete = { throw IllegalStateException("denied") },
            report = report
        )
        assertFalse(SecurePreferencesOpener.isPersistent(opened))
        assertEquals(
            listOf("OPEN_FAILED:corrupt", "DELETE_FAILED:denied", "VOLATILE_FALLBACK:still broken"),
            events
        )
    }

    @Test
    fun `degraded storage stays degraded for the rest of the process`() {
        val name = uniqueName()
        val degraded = SecurePreferencesOpener.open(
            name = name,
            create = { throw KeyStoreException("unusable") },
            delete = {}
        )
        val reopened = SecurePreferencesOpener.open(
            name = name,
            create = { throw AssertionError("a recovered keystore must not split readers and writers") },
            delete = {}
        )
        assertSame(degraded, reopened)
    }

    @Test
    fun `keystore detection follows the cause chain`() {
        val nested = RuntimeException("outer", IllegalStateException("middle", KeyStoreException("inner")))
        assertTrue(SecurePreferencesOpener.isKeystoreUnavailable(nested))
        assertFalse(SecurePreferencesOpener.isKeystoreUnavailable(GeneralSecurityException("bad tag")))
    }

    private fun uniqueName() = "secure-test-${UUID.randomUUID()}"
}
