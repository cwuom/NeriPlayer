package moe.ouom.neriplayer.ui.dialog

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
class MainActivityTrafficRiskDownloadTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun cleanPlayerShowsMobileDataConfirmationAboveRealMoreMenu() =
        assertConfirmationAboveRealPlayer(UiStyle.Clean)

    @Test
    fun materialPlayerShowsMobileDataConfirmationAboveRealMoreMenu() =
        assertConfirmationAboveRealPlayer(UiStyle.Material)

    private fun assertConfirmationAboveRealPlayer(style: UiStyle) {
        assumeComposeHostAvailable()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val preferences = runBlocking { context.dataStore.data.first() }
        val debugWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val originalStyle = runBlocking { settings.uiStyleFlow.first() }
        val originalDisclaimer = runBlocking { settings.disclaimerAcceptedFlow.first() == true }
        val originalOnboarding = runBlocking { settings.startupOnboardingCompletedFlow.first() == true }
        val originalUsbExclusive = runBlocking { settings.usbExclusivePlaybackFlow.first() }
        val originalFollowSystemDark = runBlocking { settings.followSystemDarkFlow.first() }
        val originalForceDark = runBlocking { settings.forceDarkFlow.first() }
        val queueStore = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
        val roomPrimary = runBlocking { queueStore.isRoomPrimary() }
        val queue = runBlocking { queueStore.readIfRoomPrimary() }
        val legacyQueue = listOf("last_playlist.json", "last_playback_state.json").associateWith {
            File(context.filesDir, it).takeIf(File::exists)?.readBytes()
        }
        val originalMotionStyle = NeriMotionStyle.clean
        val directory = File(context.cacheDir, "traffic-risk-layer-${System.nanoTime()}")
        check(directory.mkdirs())
        var scenario: ActivityScenario<MainActivity>? = null
        var fixtureSong: SongItem? = null
        try {
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setDefaultStartDestination(Destinations.Explore.route)
                settings.setUiStyle(style)
                settings.setThemeMode(followSystemDark = false, forceDark = true)
                settings.setNowPlayingDynamicBackgroundEnabled(false)
                settings.setNowPlayingCoverBlurBackgroundEnabled(false)
                settings.setUsbExclusivePlayback(false)
                PreviewThemePromptRepository(context).answer(enable = false)
            }
            grantRuntimePermissions(*playbackRuntimePermissions())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val localized = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))
            awaitText(localized.getString(AppR.string.nav_explore))
            val song = createLocalSong(context, directory).also { fixtureSong = it }
            scenario.onActivity { activity ->
                PlayerManager.release()
                PlayerManager.initialize(activity.application)
                PlayerManager.playPlaylist(listOf(song), startIndex = 0)
            }
            composeRule.waitUntil(15_000L) {
                PlayerManager.currentSongFlow.value?.localFilePath == song.localFilePath &&
                    PlayerManager.isPlayingFlow.value
            }
            scenario.onActivity { PlayerManager.pause() }
            composeRule.waitUntil(15_000L) { !PlayerManager.playbackControlPlayingFlow.value }
            val songName = checkNotNull(PlayerManager.currentSongFlow.value).displayName()
            awaitText(songName)
            composeRule.onNodeWithText(songName, useUnmergedTree = true).assertIsDisplayed().performNativeClick()
            settle()
            val moreLabel = localized.getString(CoreCommonR.string.nowplaying_more_options)
            composeRule.waitUntil(15_000L) {
                composeRule.onAllNodesWithContentDescription(moreLabel).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithContentDescription(moreLabel).assertIsDisplayed().performNativeClick()
            val menuLabel = localized.getString(CoreCommonR.string.music_get_info)
            awaitText(menuLabel)
            settle()

            val requests = GlobalDownloadManager.trafficRiskDownloadRequests
            check(requests is MutableSharedFlow<GlobalDownloadManager.TrafficRiskDownloadRequest>) {
                "download requests must expose the real mutable event stream for this integration fixture"
            }
            assertTrue("the app must be collecting download confirmation requests", requests.subscriptionCount.value > 0)
            val taskKeysBefore = GlobalDownloadManager.downloadTasks.value.map { it.song.stableKey() }.toSet()
            repeat(2) { attempt ->
                runBlocking {
                    requests.emit(GlobalDownloadManager.TrafficRiskDownloadRequest(
                        id = 91_000L + attempt,
                        songs = listOf(SongItem(
                            id = 91_000L,
                            name = "Synthetic Bilibili download",
                            artist = "androidTest",
                            album = "Bilibili",
                            albumId = 0L,
                            durationMs = 218_000L,
                            coverUrl = null
                        )),
                        networkType = TrafficNetworkType.MOBILE,
                        isBatch = false
                    ))
                }
                val title = localized.getString(CoreCommonR.string.traffic_risk_download_title)
                awaitText(title)
                settle()
                capture(context, "${style.name.lowercase()}-mobile-$attempt")
                composeRule.onNode(hasText(localized.getString(CoreCommonR.string.action_cancel)) and hasClickAction())
                    .assertIsDisplayed().performNativeClick()
                composeRule.waitUntil(5_000L) {
                    composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNodeWithText(menuLabel).assertIsDisplayed()
                composeRule.onNodeWithTag("nowPlayingBackButton").assertExists()
                assertEquals(taskKeysBefore, GlobalDownloadManager.downloadTasks.value.map { it.song.stableKey() }.toSet())
            }
        } finally {
            try {
                instrumentation.runOnMainSync { PlayerManager.release() }
                scenario?.close()
            } finally {
                try {
                    try {
                        runBlocking { removeFixtureStats(context, directory, listOfNotNull(fixtureSong)) }
                    } finally {
                        runBlocking {
                            if (roomPrimary && queue != null) queueStore.replaceSnapshot(queue) else queueStore.clear()
                            if (!roomPrimary) queueStore.markLegacyJsonPrimary()
                        }
                        legacyQueue.forEach { (name, bytes) ->
                            val file = File(context.filesDir, name)
                            if (bytes != null) file.writeBytes(bytes)
                            else check(!file.exists() || file.delete())
                        }
                    }
                } finally {
                    try {
                        runBlocking {
                            settings.setUiStyle(originalStyle)
                            settings.setDisclaimerAccepted(originalDisclaimer)
                            settings.setStartupOnboardingCompleted(originalOnboarding)
                            settings.setUsbExclusivePlayback(originalUsbExclusive)
                            settings.setThemeMode(originalFollowSystemDark, originalForceDark)
                            context.dataStore.updateData { preferences }
                            context.debugBuildWarningDataStore.updateData { debugWarning }
                        }
                    } finally {
                        NeriMotionStyle.clean = originalMotionStyle
                        check(directory.deleteRecursively())
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(30_000L) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(700L)
        composeRule.waitForIdle()
    }

    private fun createLocalSong(context: Context, directory: File): SongItem {
        val file = File(directory, "traffic-risk.wav")
        val pcm = ByteArray(8_000 * 60 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()
        file.outputStream().use {
            it.write(header)
            it.write(pcm)
        }
        return LocalMediaSupport.toSongItem(LocalMediaSupport.inspect(context, Uri.fromFile(file)))
    }

    private suspend fun removeFixtureStats(context: Context, directory: File, songs: List<SongItem>) {
        flushPlaybackStatsPendingWrites(context)
        val history = AppContainer.playHistoryRepo
        check(history.awaitHistoryLoaded())
        history.removeSongs(songs)
        withTimeout(10_000L) {
            history.historyFlow.first { entries ->
                entries.none { it.localFilePath?.startsWith("${directory.path}/") == true }
            }
        }
        val repository = PlaybackStatsRepository.getInstance(context)
        val keys = mutableSetOf<String>()
        var cursor: PlaybackStatsCursor? = null
        do {
            val page = repository.readPage(PlaybackStatsQuery(), after = cursor)
            page.tracks.filter { it.localFilePath?.startsWith("${directory.path}/") == true }
                .mapTo(keys) { it.identityKey }
            cursor = page.nextCursor
        } while (cursor != null)
        repository.removeTracks(keys)
        withTimeout(10_000L) {
            while (keys.any { repository.getStatForTrack(it) != null }) delay(50L)
        }
    }

    private fun capture(context: Context, stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf(String::isNotBlank) ?: return
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500L, 5_000L)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(checkNotNull(context.getExternalFilesDir(null)), "$prefix-$stage.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("MainTrafficRiskLayer", "capture=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
