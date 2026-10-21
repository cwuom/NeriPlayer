package moe.ouom.neriplayer.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.MessageDigest
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 用测试专用别名制造真实的“主密钥存在但不可用”，不触碰应用自己的主密钥 */
@RunWith(AndroidJUnit4::class)
class SecurePreferencesKeystoreFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "neri_test_unusable_${UUID.randomUUID()}"
    private val prefsName = "neri_test_secure_${UUID.randomUUID()}"

    @After
    fun tearDown() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        context.deleteSharedPreferences(prefsName)
        val identifier = MessageDigest.getInstance("SHA-256")
            .digest(prefsName.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        listOf("key", "prefs").forEach { extension ->
            val file = File(context.noBackupFilesDir, "account_preferences/$identifier.$extension")
            check(!file.exists() || file.delete()) { "Cannot clean test account storage" }
        }
    }

    @Test
    fun unusableMasterKeyIsClassifiedAsKeystoreFailureAndKeepsStoredFile() {
        generateEncryptOnlyKey(alias)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString(MARKER_KEY, MARKER_VALUE).commit()

        val directFailure = runCatching { createEncryptedPrefs() }.exceptionOrNull()
        assertTrue("security-crypto must reject the unusable key", directFailure != null)
        Log.i(TAG, "security-crypto rejected the unusable master key", directFailure)
        assertTrue(
            "unexpected failure shape: $directFailure",
            SecurePreferencesOpener.isKeystoreUnavailable(requireNotNull(directFailure))
        )

        var deleteRequested = false
        val opened = SecurePreferencesOpener.open(
            name = prefsName,
            create = ::createEncryptedPrefs,
            delete = { deleteRequested = true; context.deleteSharedPreferences(prefsName) }
        )

        assertFalse(SecurePreferencesOpener.isPersistent(opened))
        assertFalse("credentials must not be deleted for a keystore failure", deleteRequested)
        assertEquals(
            MARKER_VALUE,
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(MARKER_KEY, null)
        )
    }

    @Test
    fun unusableMasterKeyKeepsOriginalFileAndUsesPersistentAccountEncryption() {
        generateEncryptOnlyKey(alias)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString(MARKER_KEY, MARKER_VALUE).commit()
        val opened = AccountPreferencesOpener.open(
            context = context,
            name = prefsName,
            create = ::createEncryptedPrefs,
            delete = { error("account recovery must preserve the original encrypted file") }
        )
        assertTrue(AccountPreferencesOpener.isCompatibilityStorage(opened))
        assertTrue(SecurePreferencesOpener.isPersistent(opened))
        assertTrue(opened.edit().putString("synthetic_session", "synthetic-account-value").commit())
        assertEquals("synthetic-account-value", opened.getString("synthetic_session", null))
        assertEquals(MARKER_VALUE,
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(MARKER_KEY, null))
        assertTrue(opened.edit().clear().commit())
    }

    private fun createEncryptedPrefs() = EncryptedSharedPreferences.create(
        context,
        prefsName,
        MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun generateEncryptOnlyKey(alias: String) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val TAG = "NERI-SecurePrefsTest"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MARKER_KEY = "marker"
        const val MARKER_VALUE = "kept"
    }
}
