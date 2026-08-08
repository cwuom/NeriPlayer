package moe.ouom.neriplayer.util.security

import android.content.SharedPreferences
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveringEncryptedSharedPreferencesTest {

    @Test
    fun cryptoFailureWhileOpening_resetsOnlyTheAffectedStore() {
        var openCount = 0
        var resetCount = 0
        val replacement = MemoryOnlySharedPreferences()
        val preferences = RecoveringEncryptedSharedPreferences(
            storageName = "credential_store",
            logTag = "RecoveringPreferencesTest",
            opener = {
                if (openCount++ == 0) {
                    throw AEADBadTagException("backup key does not match")
                }
                replacement
            },
            resetter = { resetCount++ }
        )

        assertTrue(preferences.isDurable)
        assertEquals(2, openCount)
        assertEquals(1, resetCount)
        assertTrue(preferences.edit().putString("token", "new-token").commit())
        assertEquals("new-token", replacement.getString("token", null))
    }

    @Test
    fun cryptoFailureWhileReading_reopensAnEmptyDurableStore() {
        var openCount = 0
        var resetCount = 0
        val replacement = MemoryOnlySharedPreferences()
        val preferences = RecoveringEncryptedSharedPreferences(
            storageName = "credential_store",
            logTag = "RecoveringPreferencesTest",
            opener = {
                if (openCount++ == 0) {
                    FailingReadPreferences()
                } else {
                    replacement
                }
            },
            resetter = { resetCount++ }
        )

        assertNull(preferences.getString("token", null))
        assertTrue(preferences.isDurable)
        assertEquals(2, openCount)
        assertEquals(1, resetCount)
    }

    @Test
    fun transientOpenFailure_keepsPersistentDataUntouchedAndUsesMemoryOnlyStore() {
        var resetCount = 0
        val preferences = RecoveringEncryptedSharedPreferences(
            storageName = "credential_store",
            logTag = "RecoveringPreferencesTest",
            opener = { throw IllegalStateException("keystore temporarily unavailable") },
            resetter = { resetCount++ }
        )

        assertFalse(preferences.isDurable)
        assertEquals(0, resetCount)
        assertTrue(preferences.edit().putString("token", "session-only").commit())
        assertEquals("session-only", preferences.getString("token", null))
    }

    @Test
    fun nonDecryptionSecurityFailure_doesNotDeleteExistingCredentials() {
        var resetCount = 0
        val preferences = RecoveringEncryptedSharedPreferences(
            storageName = "credential_store",
            logTag = "RecoveringPreferencesTest",
            opener = { throw GeneralSecurityException("hardware service unavailable") },
            resetter = { resetCount++ }
        )

        assertFalse(preferences.isDurable)
        assertEquals(0, resetCount)
    }

    private class FailingReadPreferences(
        private val delegate: SharedPreferences = MemoryOnlySharedPreferences()
    ) : SharedPreferences by delegate {
        private var shouldFail = true

        override fun getString(key: String, defValue: String?): String? {
            if (shouldFail) {
                shouldFail = false
                throw AEADBadTagException("backup key does not match")
            }
            return delegate.getString(key, defValue)
        }
    }
}
