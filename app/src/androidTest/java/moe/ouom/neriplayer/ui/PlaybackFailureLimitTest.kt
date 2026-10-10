package moe.ouom.neriplayer.ui

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackFailureLimitTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val createdFiles = mutableListOf<File>()
    private var originalRepeatMode = Player.REPEAT_MODE_OFF

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        acceptStartupScreens()
        grantRuntimePermissions(*playbackRuntimePermissions())
        composeRule.runOnIdle {
            PlayerManager.release()
            PlayerManager.initialize(composeRule.activity.application)
            originalRepeatMode = PlayerManager.repeatModeFlow.value
        }
    }

    @After
    fun tearDown() {
        observerScope.cancel()
        composeRule.runOnIdle {
            cycleRepeatModeTo(originalRepeatMode)
            PlayerManager.release()
        }
        createdFiles.forEach(File::delete)
    }

    @Test
    fun repeatAllQueueOfUndecodableLocalFilesStopsAtFailureLimit() {
        val songs = (1..3).map(::createUndecodableLocalSong)
        val songChanges = AtomicInteger()
        composeRule.runOnIdle {
            cycleRepeatModeTo(Player.REPEAT_MODE_ALL)
            assertEquals(Player.REPEAT_MODE_ALL, PlayerManager.repeatModeFlow.value)
        }
        observerScope.launch {
            PlayerManager.currentSongFlow.map { song -> song?.id }.distinctUntilChanged().drop(1)
                .collect { songChanges.incrementAndGet() }
        }
        composeRule.runOnIdle {
            PlayerManager.playPlaylist(songs, startIndex = 0)
        }

        // 每首都能解析出本地 URI 但无法解码，循环队列必须在连续失败上限处停止
        val settledChanges = awaitStoppedWithoutSongChanges(songChanges)

        // 首次进入队列可能从恢复的旧曲目切换一次，其余每次切歌都对应一次失败
        assertTrue(
            "expected at most ${MAX_CONSECUTIVE_FAILURES + 1} track switches, got $settledChanges",
            settledChanges <= MAX_CONSECUTIVE_FAILURES + 1
        )
        assertTrue(!PlayerManager.isPlayingFlow.value)
    }

    /** 切歌间隙也会短暂满足停止条件，因此要求整个静默期内不再切歌 */
    private fun awaitStoppedWithoutSongChanges(songChanges: AtomicInteger): Int {
        val deadline = SystemClock.elapsedRealtime() + FAILURE_LIMIT_TIMEOUT_MS
        var observedChanges = -1
        var quietSinceMs = 0L
        while (true) {
            val currentChanges = songChanges.get()
            val nowMs = SystemClock.elapsedRealtime()
            if (currentChanges != observedChanges) {
                observedChanges = currentChanges
                quietSinceMs = nowMs
            }
            val stopped = !PlayerManager.playWhenReadyFlow.value &&
                PlayerManager.currentMediaUrlFlow.value == null
            if (stopped && nowMs - quietSinceMs >= QUIET_PERIOD_MS) return currentChanges
            assertTrue("playback kept switching tracks: changes=$currentChanges", nowMs < deadline)
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun cycleRepeatModeTo(mode: Int) {
        repeat(3) {
            if (PlayerManager.repeatModeFlow.value != mode) PlayerManager.cycleRepeatMode()
        }
    }

    private fun acceptStartupScreens() {
        val context = composeRule.activity
        val settingsRepository = SettingsRepository(context.applicationContext)
        val warningRepository = DebugBuildWarningRepository(context.applicationContext)
        runBlocking {
            warningRepository.acknowledge()
            assertTrue(warningRepository.isAcknowledged())
            settingsRepository.setDisclaimerAccepted(true)
            settingsRepository.setStartupOnboardingCompleted(true)
        }
        val mainTabLabel = context.getString(CoreCommonR.string.nav_explore)
        val warningTitle = context.getString(R.string.debug_build_warning_title)
        composeRule.waitUntil(timeoutMillis = STARTUP_SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(mainTabLabel)
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(warningTitle).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun createUndecodableLocalSong(index: Int): SongItem {
        val file = File(composeRule.activity.cacheDir, "failure_limit_$index.mp3")
        file.writeBytes(ByteArray(64 * 1024) { position -> (position * 31 + index).toByte() })
        createdFiles += file
        return SongItem(
            id = 9_000L + index,
            name = "failure_limit_$index",
            artist = "androidTest",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null,
            mediaUri = Uri.fromFile(file).toString(),
            localFilePath = file.absolutePath
        )
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 10
        const val STARTUP_SCREEN_TIMEOUT_MS = 30_000L
        const val FAILURE_LIMIT_TIMEOUT_MS = 45_000L
        const val QUIET_PERIOD_MS = 3_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
