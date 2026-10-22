package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.config.ConfigBackupPasswordException
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.data.config.ConfigImportCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigBackupCryptoDeviceTest {
    @Test
    fun fixedNodeEnvelopeImportsWithAndroidJcaAndRejectsWrongOrCancelledPasswords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backup = File.createTempFile("config-crypto-device-", ".json", context.cacheDir)
        try {
            backup.writeText(ENVELOPE, Charsets.UTF_8)
            val uri = Uri.fromFile(backup)
            val manager = ConfigFileManager(context)
            val password = "demo".toCharArray()

            val restored = manager.importConfig(uri, requestPassword = { password }).getOrThrow()

            assertEquals(0, restored.restoredSettingsCount)
            assertEquals(0, restored.restoredListenTogetherCount)
            assertEquals(0, restored.restoredAuthCount)
            assertEquals(0, restored.restoredSyncCount)
            assertFalse(restored.requiresActivityRecreate)
            assertTrue(password.all { it == '\u0000' })

            val wrongPassword = "incorrect".toCharArray()
            val rejected = manager.importConfig(uri, requestPassword = { wrongPassword })

            assertTrue(rejected.exceptionOrNull() is ConfigBackupPasswordException)
            assertTrue(wrongPassword.all { it == '\u0000' })

            val cancelled = manager.importConfig(uri, requestPassword = { null })

            assertTrue(cancelled.exceptionOrNull() is ConfigImportCancelledException)
            assertEquals(ENVELOPE, backup.readText(Charsets.UTF_8))
        } finally {
            assertTrue("cannot remove the test backup", !backup.exists() || backup.delete())
        }
    }

    private companion object {
        // 独立使用 Node crypto 生成，未知语言码让恢复跳过语言写入
        const val ENVELOPE = """{"kind":"moe.ouom.neriplayer.config.encrypted","formatVersion":1,"kdf":"PBKDF2-HMAC-SHA256","iterations":600000,"cipher":"AES-256-GCM","salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"EBESExQVFhcYGRob","ciphertext":"0dGl53h52etquT6aUagy2TU0/og9yZ3hHlwX2XUUxO+FDmbRilVbqi6FU7N76UXa+0OUUMqXQp01IkQC+YyaiRjzkCzHC6Yvu9bEMQo2xMP7F0pZEV525i913pALjtQj86ElaYAl/uM1efnlwGPayuW07Kr19wBtD/5PUa22"}"""
    }
}
