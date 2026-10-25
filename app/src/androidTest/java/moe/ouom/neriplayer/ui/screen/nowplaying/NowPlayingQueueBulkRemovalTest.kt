package moe.ouom.neriplayer.ui.screen.nowplaying

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingQueueBulkRemovalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    @Test
    fun selectAllAndRemoveFiveHundredSongsPublishesOnceWithoutBlockingMainThread() {
        assumeTrue("仅在专用模拟器运行", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeComposeHostAvailable()
        val context = composeRule.activity.applicationContext
        assumeNoSyncTargets(context)
        val savedQueue = runBlocking { SavedQueue.read(context) }
        val preferences = readPlaybackPreferenceSnapshotSync(context)
        val directory = File.createTempFile("queue-bulk-removal-", "", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val heartbeat = MainThreadHeartbeat()
        var visible by mutableStateOf(true)
        var songs = emptyList<SongItem>()
        var failure: Throwable? = null
        try {
            songs = createLocalQueue(directory)
            composeRule.runOnUiThread {
                if (!AppContainer.isInitialized()) AppContainer.initialize(composeRule.activity.application)
                PlayerManager.release()
                PlayerManager.initializePreloaded(
                    composeRule.activity.application,
                    preferences,
                    RestoredPlayerStateSnapshot(
                        playlist = emptyList(), currentIndex = -1, currentMediaUrl = null,
                        repeatMode = Player.REPEAT_MODE_OFF, shuffleEnabled = false,
                        shuffleRestorePlaylist = null, shuffleRestoreIndex = -1,
                        resumePositionMs = 0L, shouldResumePlayback = false,
                        originalPlaylistSize = 0, persistedIndex = -1
                    )
                )
            }
            composeRule.waitUntil(UiTimeoutMs) { PlayerManager.localPlaylistsReadyFlow.value }
            composeRule.runOnUiThread { PlayerManager.playPlaylist(songs, startIndex = 0) }
            composeRule.waitUntil(UiTimeoutMs) {
                PlayerManager.currentQueueFlow.value.size == 500 && PlayerManager.isPlayingFlow.value
            }
            composeRule.setContent {
                val queue by PlayerManager.currentQueueFlow.collectAsState()
                val revision by PlayerManager.currentQueueDisplayRevisionFlow.collectAsState()
                val display = remember(queue, revision) { PlayerManager.currentQueueDisplaySnapshot() }
                NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                    seedColorHex = "6750A4", animateColors = false) {
                    if (visible) {
                        NowPlayingQueueSheet(
                            displayedQueueItems = display.items,
                            currentIndexInDisplay = display.currentDisplayIndex,
                            offlineMode = true,
                            allowQueueReorder = true,
                            onDismissRequest = { visible = false }
                        )
                    }
                }
            }
            UiFailureDiagnostics.onFailure("now-playing-queue-bulk-removal") {
                // 本地元数据会更新歌曲标题，按队列行号定位长按目标
                composeRule.onNodeWithText("1").assertIsDisplayed()
                    .performTouchInput { longClick() }
                composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_select_all))
                    .performClick()
                composeRule.onNodeWithText(context.resources.getQuantityString(
                    CoreCommonR.plurals.common_selected_count, 500, 500
                )).assertIsDisplayed()
                val revisionBeforeRemoval = PlayerManager.currentQueueDisplayRevisionFlow.value
                composeRule.runOnIdle { heartbeat.start() }
                val startedAt = SystemClock.uptimeMillis()
                composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.nowplaying_queue_remove))
                    .performClick()
                composeRule.runOnIdle {
                    heartbeat.stop()
                    assertTrue(PlayerManager.currentQueueFlow.value.isEmpty())
                    assertNull(PlayerManager.currentSongFlow.value)
                    assertFalse(PlayerManager.playWhenReadyFlow.value)
                    assertFalse(PlayerManager.isPlayingFlow.value)
                    assertEquals(revisionBeforeRemoval + 1L,
                        PlayerManager.currentQueueDisplayRevisionFlow.value)
                }
                val elapsedMs = SystemClock.uptimeMillis() - startedAt
                Log.i("QueueBulkRemovalTest", "queue=500 deleteElapsedMs=$elapsedMs " +
                    "maximumMainThreadGapMs=${heartbeat.maximumDelayMs}")
                assertTrue("500 首批量删除耗时 ${elapsedMs}ms", elapsedMs < 4_000L)
                assertTrue("主线程最长未响应 ${heartbeat.maximumDelayMs}ms",
                    heartbeat.maximumDelayMs < 2_000L)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { composeRule.runOnUiThread { heartbeat.stop(); visible = false } }
            cleanup { composeRule.waitForIdle() }
            cleanup { composeRule.runOnUiThread { PlayerManager.release() } }
            cleanup { runBlocking { removeFixtureStatistics(context, directory, songs) } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { check(directory.deleteRecursively()) }
            cleanupFailures.forEach { failure?.addSuppressed(it) }
            if (failure == null && cleanupFailures.isNotEmpty()) {
                throw AssertionError("批量删除回归测试清理失败", cleanupFailures.first())
            }
        }
    }

    private fun createLocalQueue(directory: File): List<SongItem> {
        val audio = File(directory, "synthetic.wav")
        val pcm = ByteArray(8_000 * 180 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
        }.array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        return List(500) { index ->
            SongItem(
                id = -9_800_000L - index, name = "Queue bulk fixture $index", artist = "Synthetic fixture",
                album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, albumId = 0L, durationMs = 180_000L,
                coverUrl = null, mediaUri = Uri.fromFile(audio).toString(), localFilePath = audio.path
            )
        }
    }

    private fun assumeNoSyncTargets(context: Context) {
        val noTargets = runCatching {
            val keysets = setOf("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__")
            val webDavEmpty = context.getSharedPreferences("webdav_secure_prefs", Context.MODE_PRIVATE)
                .all.keys.all { it in keysets }
            val github = SecureTokenStorage(context)
            webDavEmpty && github.isPersistent && github.getToken().isNullOrEmpty() &&
                github.getRepoOwner().isNullOrEmpty() && github.getRepoName().isNullOrEmpty()
        }.getOrDefault(false)
        assumeTrue("专用模拟器不能配置 GitHub 或 WebDAV 同步目标", noTargets)
    }

    private suspend fun removeFixtureStatistics(context: Context, directory: File, songs: List<SongItem>) {
        flushPlaybackStatsPendingWrites(context)
        val history = AppContainer.playHistoryRepo
        check(history.awaitHistoryLoaded())
        history.removeSongs(songs)
        withTimeout(UiTimeoutMs) {
            history.historyFlow.first { entries ->
                entries.none { it.localFilePath?.startsWith("${directory.path}/") == true }
            }
        }
        val repository = AppContainer.playbackStatsRepo
        val keys = mutableSetOf<String>()
        var cursor: PlaybackStatsCursor? = null
        do {
            val page = repository.readPage(PlaybackStatsQuery(), after = cursor)
            page.tracks.filter { it.localFilePath?.startsWith("${directory.path}/") == true }
                .mapTo(keys) { it.identityKey }
            cursor = page.nextCursor
        } while (cursor != null)
        repository.removeTracks(keys)
        withTimeout(UiTimeoutMs) {
            while (keys.any { repository.getStatForTrack(it) != null }) delay(50L)
        }
    }

    private class MainThreadHeartbeat {
        private val handler = Handler(Looper.getMainLooper())
        private var lastTickMs = 0L
        var maximumDelayMs = 0L
            private set
        private val tick = object : Runnable {
            override fun run() {
                recordTick()
                handler.postDelayed(this, 20L)
            }
        }

        fun start() {
            lastTickMs = SystemClock.uptimeMillis()
            handler.postDelayed(tick, 20L)
        }

        fun stop() {
            if (lastTickMs != 0L) recordTick()
            handler.removeCallbacks(tick)
        }

        private fun recordTick() {
            val nowMs = SystemClock.uptimeMillis()
            maximumDelayMs = maxOf(maximumDelayMs, nowMs - lastTickMs)
            lastTickMs = nowMs
        }
    }

    private data class SavedQueue(val roomPrimary: Boolean, val state: PersistedState?, val legacy: Map<String, ByteArray?>) {
        suspend fun restore(context: Context) {
            val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
            if (roomPrimary && state != null) store.replaceSnapshot(state) else store.clear()
            legacy.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes != null) file.writeBytes(bytes) else check(!file.exists() || file.delete())
            }
            if (!roomPrimary) store.markLegacyJsonPrimary(System.currentTimeMillis())
        }

        companion object {
            suspend fun read(context: Context): SavedQueue {
                val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
                return SavedQueue(store.isRoomPrimary(), store.readIfRoomPrimary(),
                    listOf("last_playlist.json", "last_playback_state.json").associateWith {
                        File(context.filesDir, it).takeIf(File::exists)?.readBytes()
                    })
            }
        }
    }

    private companion object {
        const val UiTimeoutMs = 15_000L
    }
}
