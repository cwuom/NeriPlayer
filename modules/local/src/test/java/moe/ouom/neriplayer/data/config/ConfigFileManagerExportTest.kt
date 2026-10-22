package moe.ouom.neriplayer.data.config

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.common.storage.VolatileSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigFileManagerExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearSyncPreferences() {
        context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        VolatileSharedPreferences.shared("github_secure_prefs").edit().clear().commit()
    }

    @After
    fun restoreSyncPreferences() = clearSyncPreferences()

    @Test
    fun `default export omits account sections instead of exporting empty account snapshots`() = runBlocking {
        val destination = folder.newFile("config.json")

        ConfigFileManager(context).exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertTrue("settings" in root)
        for (section in listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")) {
            assertFalse("unexpected account section: $section", section in root)
        }
    }

    @Test
    fun `default export preserves the unmigrated legacy batched history interval`() = runBlocking {
        val legacyPreferences = VolatileSharedPreferences.shared("github_secure_prefs")
        legacyPreferences.edit().putString("play_history_update_mode", "BATCHED").commit()
        val destination = folder.newFile("legacy-batched.json")

        ConfigFileManager(context).exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals(
            "EVERY_10_MINUTES",
            root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content
        )
        assertFalse("gitHubSync" in root)
        assertEquals("BATCHED", legacyPreferences.getString("play_history_update_mode", null))
    }

    @Test
    fun `default export preserves an unmigrated legacy thirty minute history interval`() = runBlocking {
        VolatileSharedPreferences.shared("github_secure_prefs").edit()
            .putString("play_history_update_mode", "BATCHED_30").commit()
        val destination = folder.newFile("legacy-thirty.json")

        ConfigFileManager(context).exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals(
            "EVERY_30_MINUTES",
            root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content
        )
    }

    @Test
    fun `default export keeps the shared history interval over a different legacy interval`() = runBlocking {
        VolatileSharedPreferences.shared("github_secure_prefs").edit()
            .putString("play_history_update_mode", "BATCHED").commit()
        context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)
            .edit().putString("play_history_update_mode", "EVERY_15_MINUTES").commit()
        val destination = folder.newFile("shared-interval.json")

        ConfigFileManager(context).exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals(
            "EVERY_15_MINUTES",
            root.getValue("syncPreferences").jsonObject.getValue("playHistoryUpdateMode").jsonPrimitive.content
        )
    }
}
