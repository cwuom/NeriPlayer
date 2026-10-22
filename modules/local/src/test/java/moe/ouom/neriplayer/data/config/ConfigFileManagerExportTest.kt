package moe.ouom.neriplayer.data.config

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigFileManagerExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `default export omits account sections instead of exporting empty account snapshots`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destination = folder.newFile("config.json")

        ConfigFileManager(context).exportConfig(Uri.fromFile(destination)).getOrThrow()

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertTrue("settings" in root)
        for (section in listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")) {
            assertFalse("unexpected account section: $section", section in root)
        }
    }
}
