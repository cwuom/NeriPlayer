// 测试拦截应用仍在使用的加密存储 API，避免访问真实 Keystore
@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStoreException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.withSettings

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigFileManagerExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var legacyPreferences: SharedPreferences
    private lateinit var masterKeyBuilders: MockedConstruction<MasterKey.Builder>
    private lateinit var encryptedPreferences: MockedStatic<EncryptedSharedPreferences>
    private var secureStorageAvailable = true

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val secureDirectory = folder.newFolder("secure-storage")
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = secureDirectory
        }
        context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        legacyPreferences = context.getSharedPreferences("legacy_history_export_fixture", Context.MODE_PRIVATE)
        legacyPreferences.edit().clear().commit()
        val masterKey = mock(MasterKey::class.java)
        masterKeyBuilders = mockConstruction(MasterKey.Builder::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            `when`(builder.build()).thenAnswer {
                if (!secureStorageAvailable) throw KeyStoreException("temporarily unavailable")
                masterKey
            }
        }
        encryptedPreferences = mockStatic(EncryptedSharedPreferences::class.java)
        encryptedPreferences.`when`<SharedPreferences> {
            EncryptedSharedPreferences.create(
                context, "github_secure_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.thenReturn(legacyPreferences)
    }

    @After
    fun tearDown() {
        encryptedPreferences.close()
        masterKeyBuilders.close()
        context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        legacyPreferences.edit().clear().commit()
    }

    @Test
    fun `default export omits account sections instead of exporting empty account snapshots`() = runTest {
        val destination = folder.newFile("config.json")

        manager().exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertTrue("settings" in root)
        for (section in listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")) {
            assertFalse("unexpected account section: $section", section in root)
        }
    }

    @Test
    fun `default export preserves the unmigrated legacy batched history interval`() = runTest {
        legacyPreferences.edit().putString("play_history_update_mode", "BATCHED").commit()
        val destination = folder.newFile("legacy-batched.json")

        manager().exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals(
            "EVERY_10_MINUTES",
            root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content
        )
        assertFalse("gitHubSync" in root)
        assertEquals("BATCHED", legacyPreferences.getString("play_history_update_mode", null))
    }

    @Test
    fun `default export preserves an unmigrated legacy thirty minute history interval`() = runTest {
        legacyPreferences.edit()
            .putString("play_history_update_mode", "BATCHED_30").commit()
        val destination = folder.newFile("legacy-thirty.json")

        manager().exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals(
            "EVERY_30_MINUTES",
            root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content
        )
    }

    @Test
    fun `default export keeps the shared history interval over a different legacy interval`() = runTest {
        legacyPreferences.edit()
            .putString("play_history_update_mode", "BATCHED").commit()
        context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)
            .edit().putString("play_history_update_mode", "EVERY_15_MINUTES").commit()
        val destination = folder.newFile("shared-interval.json")

        manager().exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals(
            "EVERY_15_MINUTES",
            root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content
        )
    }

    @Test
    fun `default export tolerates an unavailable legacy interval without consuming its migration`() = runTest {
        secureStorageAvailable = false
        legacyPreferences.edit().putString("play_history_update_mode", "BATCHED_30").commit()
        val destination = folder.newFile("unavailable-legacy.json")

        manager().exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals("IMMEDIATE", root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content)
        assertFalse("gitHubSync" in root)
        assertFalse(context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE).contains("play_history_update_mode"))
        assertEquals("BATCHED_30", legacyPreferences.getString("play_history_update_mode", null))

        secureStorageAvailable = true
        manager().exportConfig(Uri.fromFile(destination)).getOrThrow()

        val recovered = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals("EVERY_30_MINUTES", recovered.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content)
    }

    @Test
    fun `account export still fails when secure sync storage is unavailable`() = runTest {
        secureStorageAvailable = false
        val destination = folder.newFile("unavailable-account.json")

        val result = manager().exportConfig(Uri.fromFile(destination), ConfigExportOptions(includeLoginState = true))

        assertTrue(result.exceptionOrNull() is SyncStorageUnavailableException)
        assertEquals("", destination.readText())
    }

    private fun TestScope.manager() = ConfigFileManager(context, ioDispatcher = StandardTestDispatcher(testScheduler))
}
