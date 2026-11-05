package moe.ouom.neriplayer.activity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.core.startup.navigation.StartDestinationSnapshotProvider
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.navigation.DEFAULT_START_DESTINATION
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.EmulatorOfflineNetworkFixture
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityStartDestinationRestorationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var tabLabels = emptyMap<String, String>()

    @Test
    fun changingStartDestinationKeepsRestoredTabBackStack() = verifyRestoredTabBackStack(true)

    @Test
    fun unchangedStartDestinationKeepsRestoredTabBackStack() = verifyRestoredTabBackStack(false)

    @Test
    fun freshLibraryLaunchKeepsLibraryAsTabBackTarget() =
        withPreparedActivity(Destinations.Library) {
            selectTab(Destinations.Settings)
            selectTab(Destinations.Explore)
            pressBackTo(Destinations.Library)
        }

    private fun verifyRestoredTabBackStack(changeStartDestination: Boolean) =
        withPreparedActivity(Destinations.Home) { scenario ->
            selectTab(Destinations.Settings)
            if (changeStartDestination) {
                runBlocking { SettingsRepository(context).setDefaultStartDestination("library") }
                assertEquals("library", StartDestinationSnapshotProvider.read(context, false)?.startDestination)
            }
            scenario.recreate()
            waitForSelected(Destinations.Settings)
            selectTab(Destinations.Explore)
            pressBackTo(Destinations.Home)
        }

    private fun selectTab(destination: Destinations) {
        composeRule.onNode(tabMatcher(destination)).performClick()
        waitForSelected(destination)
    }

    private fun pressBackTo(destination: Destinations) {
        // 标签已选中后再等待提交，避免返回早于 pending route 清理
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNode(tabMatcher(destination)).assertIsSelected()
    }

    private fun waitForSelected(destination: Destinations) {
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodes(tabMatcher(destination)).fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Selected) == true }
        }
        composeRule.waitForIdle()
    }

    private fun tabMatcher(destination: Destinations): SemanticsMatcher {
        val navigationHost = hasAnyAncestor(hasTestTag("appNavigationRail")) or
            hasAnyAncestor(hasTestTag("appBottomNavigation"))
        val label = tabLabels.getValue(destination.route)
        val tabLabel = hasText(label) or hasContentDescription(label)
        return tabLabel and hasClickAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and navigationHost
    }

    private fun withPreparedActivity(
        startDestination: Destinations,
        verify: (ActivityScenario<MainActivity>) -> Unit
    ) {
        assumeTrue("启动恢复回归只操作 API 30+ 模拟器", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeComposeHostAvailable()
        val savedSettings = runBlocking { context.dataStore.data.first() }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val savedCaches = CacheNames.map { SavedCache.read(context, it) }
        val savedMotion = NeriMotionStyle.clean
        val serviceWasActive = AudioPlayerService.isInstanceActiveForDiagnostics()
        val pendingCrashFlag = checkNotNull(ExceptionHandler.resolveCrashDirectory(context))
            .resolve("pending_startup_crash.flag")
        val savedCrashFlag = pendingCrashFlag.takeIf { it.isFile }?.readBytes()
        var scenario: ActivityScenario<MainActivity>? = null
        var networkFixture: EmulatorOfflineNetworkFixture? = null
        var failure: Throwable? = null
        try {
            networkFixture = EmulatorOfflineNetworkFixture.start(context)
            check(!pendingCrashFlag.exists() || pendingCrashFlag.delete())
            val settings = SettingsRepository(context)
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                PreviewThemePromptRepository(context).answer(enable = false)
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setUiStyle(UiStyle.Material)
                settings.setHomeCardTrending(true)
                settings.setUsbExclusivePlayback(false)
                settings.setDefaultStartDestination(startDestination.route)
            }
            assertEquals(startDestination.route,
                StartDestinationSnapshotProvider.read(context, false)?.startDestination)
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity ->
                tabLabels = listOf(Destinations.Home, Destinations.Explore,
                    Destinations.Library, Destinations.Settings).associate { destination ->
                    destination.route to activity.getString(destination.labelResId)
                }
            }
            UiFailureDiagnostics.onFailure("start-destination-${startDestination.route}") {
                waitForSelected(startDestination)
                verify(activeScenario)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { scenario?.close() }
            if (!serviceWasActive) cleanup {
                instrumentation.runOnMainSync {
                    context.stopService(Intent(context, AudioPlayerService::class.java))
                }
                composeRule.waitUntil(UiTimeoutMs) { !AudioPlayerService.isInstanceActiveForDiagnostics() }
            }
            cleanup {
                val savedRoute = savedSettings[SettingsKeys.DEFAULT_START_DESTINATION]
                    ?: DEFAULT_START_DESTINATION
                val currentRoute = StartDestinationSnapshotProvider.read(context, false)?.startDestination
                val barrierRoute = listOf("home", "library", "explore", "settings")
                    .first { it != savedRoute && it != currentRoute }
                try {
                    // 直接写 DataStore，等 observer 最后一份快照写完后才能恢复原缓存
                    runBlocking {
                        context.dataStore.edit { it[SettingsKeys.DEFAULT_START_DESTINATION] = barrierRoute }
                    }
                    composeRule.waitUntil(UiTimeoutMs) {
                        StartDestinationSnapshotProvider.read(context, false)?.startDestination == barrierRoute
                    }
                } finally {
                    runBlocking {
                        context.dataStore.updateData { savedSettings }
                        context.debugBuildWarningDataStore.updateData { savedWarning }
                    }
                }
                composeRule.waitUntil(UiTimeoutMs) {
                    StartDestinationSnapshotProvider.read(context, false)?.startDestination == savedRoute
                }
            }
            savedCaches.forEach { saved -> cleanup { saved.restore(context) } }
            cleanup {
                if (savedCrashFlag != null) pendingCrashFlag.writeBytes(savedCrashFlag)
                else check(!pendingCrashFlag.exists() || pendingCrashFlag.delete())
            }
            cleanup { networkFixture?.close() }
            NeriMotionStyle.clean = savedMotion
            if (failure != null) cleanupFailures.forEach(failure::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private data class SavedCache(val name: String, val values: Map<String, Any>) {
        fun restore(context: Context) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, stringSet(value))
                    else -> error("不支持的同步缓存类型: cache=$name, key=$key")
                }
            }
            assertTrue("应恢复同步缓存: $name", editor.commit())
            assertEquals(values, copyValues(preferences))
        }

        companion object {
            fun read(context: Context, name: String) = SavedCache(name,
                copyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE)))

            private fun copyValues(preferences: SharedPreferences): Map<String, Any> =
                preferences.all.mapValues { (key, value) ->
                    when (value) {
                        is Boolean, is String, is Int, is Long, is Float -> value
                        is Set<*> -> stringSet(value)
                        else -> error("不支持的同步缓存类型: key=$key")
                    }
                }

            private fun stringSet(value: Set<*>): Set<String> = value.map { entry ->
                check(entry is String) { "同步缓存集合必须只包含字符串" }
                entry
            }.toSet()
        }
    }

    private companion object {
        const val UiTimeoutMs = 30_000L
        val CacheNames = listOf("startup_progress_cache", "bootstrap_settings_snapshot",
            "theme_snapshot_cache", "playback_snapshot_cache", "start_destination_snapshot_cache")
    }
}
