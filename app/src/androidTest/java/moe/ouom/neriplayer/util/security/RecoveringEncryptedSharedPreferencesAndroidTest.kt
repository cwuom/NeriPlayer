package moe.ouom.neriplayer.util.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_PREFS_NAME = "recovering_encrypted_preferences_test"
private const val TEST_MASTER_KEY_ALIAS = "recovering_encrypted_preferences_test_key"

@RunWith(AndroidJUnit4::class)
class RecoveringEncryptedSharedPreferencesAndroidTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        clearTestStorage()
    }

    @After
    fun tearDown() {
        clearTestStorage()
    }

    @Test
    fun replacedKeystoreKey_resetsUnreadableCredentialsWithoutCrashing() {
        val initial = openPreferences()
        assertTrue(initial.edit().putString("secret", "old-value").commit())
        assertEquals("old-value", initial.getString("secret", null))

        deleteTestMasterKey()

        val recovered = openPreferences()
        assertNull(recovered.getString("secret", null))
        assertTrue(recovered.isDurable)
        assertTrue(recovered.edit().putString("secret", "new-value").commit())

        val reopened = openPreferences()
        assertEquals("new-value", reopened.getString("secret", null))
    }

    private fun openPreferences(): RecoveringEncryptedSharedPreferences {
        return RecoveringEncryptedSharedPreferences(
            context = context,
            storageName = TEST_PREFS_NAME,
            logTag = "RecoveringPreferencesAndroidTest",
            masterKeyAlias = TEST_MASTER_KEY_ALIAS
        )
    }

    private fun clearTestStorage() {
        context.deleteSharedPreferences(TEST_PREFS_NAME)
        deleteTestMasterKey()
    }

    private fun deleteTestMasterKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(TEST_MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(TEST_MASTER_KEY_ALIAS)
        }
    }
}
