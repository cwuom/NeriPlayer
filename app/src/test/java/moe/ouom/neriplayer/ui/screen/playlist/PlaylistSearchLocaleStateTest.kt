package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.util.search.playlistSearchValues
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistSearchLocaleStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val songs = listOf(
        SongItem(1L, "Track", "Artist", "本地文件", 0L, 60_000L, null)
    )

    @Test
    fun `same playlist and query use current locale search tokens after language round trips`() {
        val locale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        val reads = AtomicInteger()
        composeRule.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(locale.value)
            }
            val resources = context.createConfigurationContext(configuration).resources
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalResources provides resources
            ) {
                val currentContext = rememberPlaylistSearchContext()
                val results = rememberPlaylistSearchResults(
                    query = "local files",
                    items = songs,
                    tokens = {
                        reads.incrementAndGet()
                        it.playlistSearchValues(currentContext)
                    }
                )
                Text("results=" + results.joinToString { it.id.toString() })
            }
        }
        composeRule.waitUntil(5_000) { reads.get() == 1 }
        waitForText("results=")
        assertEquals(1, reads.get())
        composeRule.runOnIdle { locale.value = Locale.ENGLISH }
        waitForText("results=1")
        assertEquals(2, reads.get())
        composeRule.runOnIdle { locale.value = Locale.SIMPLIFIED_CHINESE }
        waitForText("results=")
        composeRule.waitUntil(5_000) { reads.get() == 3 }
    }

    @Test
    fun `search follows UI resources when the host context and configuration stay English`() {
        val hostConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val hostContext = context.createConfigurationContext(hostConfiguration)
        val uiLocale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        val reads = AtomicInteger()
        composeRule.setContent {
            val uiConfiguration = Configuration(hostConfiguration).apply {
                setLocale(uiLocale.value)
            }
            val resources = hostContext.createConfigurationContext(uiConfiguration).resources
            CompositionLocalProvider(
                LocalContext provides hostContext,
                LocalConfiguration provides hostConfiguration,
                LocalResources provides resources
            ) {
                val searchContext = rememberPlaylistSearchContext()
                val results = rememberPlaylistSearchResults(
                    query = "local files",
                    items = songs,
                    tokens = {
                        reads.incrementAndGet()
                        it.playlistSearchValues(searchContext)
                    }
                )
                Text("visible=" + stringResource(CoreCommonR.string.local_files))
                Text("results=" + results.joinToString { it.id.toString() })
            }
        }
        composeRule.waitUntil(5_000) { reads.get() == 1 }
        waitForText("visible=本地文件")
        waitForText("results=")
        composeRule.runOnIdle { uiLocale.value = Locale.ENGLISH }
        waitForText("visible=Local Files")
        waitForText("results=1")
        assertEquals(2, reads.get())
        composeRule.runOnIdle { uiLocale.value = Locale.SIMPLIFIED_CHINESE }
        waitForText("visible=本地文件")
        waitForText("results=")
        try {
            composeRule.waitUntil(5_000) { reads.get() == 3 }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError(
                "Expected exactly 3 index reads after returning to Chinese, actual=${reads.get()}",
                failure
            )
        }
    }

    @Test
    fun `recomposing token lambdas in the same locale keeps the existing index`() {
        val tick = mutableIntStateOf(0)
        val reads = AtomicInteger()
        composeRule.setContent {
            val value = tick.intValue
            val results = rememberPlaylistSearchResults(
                query = "Track",
                items = songs,
                tokens = {
                    reads.incrementAndGet()
                    listOf(it.name)
                }
            )
            Text("$value:" + results.joinToString { it.id.toString() })
        }
        waitForText("0:1")
        composeRule.waitUntil(5_000) { reads.get() == 1 }
        composeRule.runOnIdle { tick.intValue = 1 }
        waitForText("1:1")
        assertEquals(1, reads.get())
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
