package moe.ouom.neriplayer.ui.screen.nowplaying

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
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
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.displayName
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
        withPlaybackFixture(500) {
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
                composeRule.runOnIdle { service.updates.reset(); heartbeat.start() }
                val startedAt = SystemClock.uptimeMillis()
                composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.nowplaying_queue_remove))
                    .performClick()
                composeRule.waitUntil(UiTimeoutMs) {
                    service.updates.latestQueue?.isEmpty() == true && service.controller.queue?.isEmpty() == true
                }
                composeRule.runOnIdle {
                    heartbeat.stop()
                    assertTrue(PlayerManager.currentQueueFlow.value.isEmpty())
                    assertNull(PlayerManager.currentSongFlow.value)
                    assertFalse(PlayerManager.playWhenReadyFlow.value)
                    assertFalse(PlayerManager.isPlayingFlow.value)
                    assertEquals(revisionBeforeRemoval + 1L,
                        PlayerManager.currentQueueDisplayRevisionFlow.value)
                }
                assertRemovalPerformance(500, 500, startedAt)
                assertSingleQueueCallback()
            }
        }
    }

    @Test
    fun removeLastFiveHundredOfThreeThousandSongsKeepsPlaybackAndPublishesSessionOnce() {
        withPlaybackFixture(3_000) {
            composeRule.runOnUiThread { PlayerManager.seekTo(30_000L) }
            composeRule.waitUntil(UiTimeoutMs) {
                PlayerManager.playbackPositionFlow.value >= 30_000L && PlayerManager.isPlayingFlow.value
            }
            val queueBefore = PlayerManager.currentQueueFlow.value
            val windowBefore = checkNotNull(service.controller.queue).toList()
            val revisionBefore = PlayerManager.currentQueueDisplayRevisionFlow.value
            var positionBefore = 0L
            var startedAt = 0L
            composeRule.runOnIdle {
                val songBefore = PlayerManager.currentSongFlow.value
                positionBefore = PlayerManager.playbackPositionFlow.value
                service.updates.reset()
                heartbeat.start()
                startedAt = SystemClock.uptimeMillis()
                PlayerManager.removeQueueItems((2_500 until 3_000).toSet())
                assertEquals(songBefore, PlayerManager.currentSongFlow.value)
                assertEquals("删除非当前歌曲不能重置进度", positionBefore, PlayerManager.playbackPositionFlow.value)
                assertTrue(PlayerManager.isPlayingFlow.value)
                assertTrue(PlayerManager.playWhenReadyFlow.value)
            }
            composeRule.waitUntil(UiTimeoutMs) {
                service.updates.latestQueue?.size == SessionQueueWindowSize &&
                    service.controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            composeRule.runOnIdle {
                heartbeat.stop()
                assertEquals(queueBefore.take(2_500), PlayerManager.currentQueueFlow.value)
                assertEquals(revisionBefore + 1L, PlayerManager.currentQueueDisplayRevisionFlow.value)
                val sessionQueue = checkNotNull(service.controller.queue)
                assertEquals(windowBefore.map { it.queueId }, sessionQueue.map { it.queueId })
                assertEquals(PlayerManager.currentQueueFlow.value.take(SessionQueueWindowSize).map { it.displayName() },
                    sessionQueue.map { it.description.title.toString() })
                assertTrue(PlayerManager.isPlayingFlow.value)
                assertTrue(PlayerManager.playWhenReadyFlow.value)
                val elapsedMs = SystemClock.uptimeMillis() - startedAt
                val positionAfter = PlayerManager.playbackPositionFlow.value
                assertTrue("删除后进度必须继续前进: before=$positionBefore after=$positionAfter",
                    positionAfter >= positionBefore && positionAfter <= positionBefore + elapsedMs + 1_000L)
            }
            assertRemovalPerformance(3_000, 500, startedAt)
            assertSingleQueueCallback()
        }
    }

    private fun withPlaybackFixture(queueSize: Int, block: PlaybackFixture.() -> Unit) {
        assumeTrue("仅在专用模拟器运行", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeComposeHostAvailable()
        val context = composeRule.activity.applicationContext
        assumeNoSyncTargets(context)
        assumeFalse("已有播放服务时不干预其生命周期", AudioPlayerService.isInstanceActiveForDiagnostics())
        assumeFalse("已有播放时不干预播放器", PlayerManager.isPlayingFlow.value ||
            PlayerManager.playWhenReadyFlow.value || PlayerManager.playbackControlPlayingFlow.value)
        assumeFalse("安全模式不创建播放服务", PlayerDependencies.presentation.shouldEnterSafeMode(context))
        val savedQueue = runBlocking { SavedQueue.read(context) }
        val languagePreferences = context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)
        val savedLanguage = languagePreferences.getString("selected_language", null)
        val savedDefaultLocale = Locale.getDefault()
        val savedPlatformLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
        } else null
        val preferences = readPlaybackPreferenceSnapshotSync(context)
        val directory = File.createTempFile("queue-bulk-removal-", "", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val fixture = PlaybackFixture(context)
        var songs = emptyList<SongItem>()
        var failure: Throwable? = null
        try {
            songs = createLocalQueue(directory, queueSize)
            composeRule.runOnUiThread {
                assertNull("初始化队列前不能继承其它测试的服务", activeService())
                if (!AppContainer.isInitialized()) AppContainer.initialize(composeRule.activity.application)
                PlayerManager.release()
                PlayerManager.initializePreloaded(
                    composeRule.activity.application,
                    preferences.copy(maxCacheSizeBytes = 0L, usbExclusivePlayback = false),
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
            fixture.service.bind()
            val serviceContext = checkNotNull(activeService())
            val mismatch = if (serviceContext.resources.configuration.locales[0].language == "en") {
                LanguageManager.Language.CHINESE
            } else {
                LanguageManager.Language.ENGLISH
            }
            // 不直接调用平台语言接口，生产路径可能继续同步应用语言
            check(languagePreferences.edit().putString("selected_language", mismatch.code).commit())
            composeRule.runOnUiThread { PlayerManager.playPlaylist(songs, startIndex = 0) }
            composeRule.waitUntil(UiTimeoutMs) {
                PlayerManager.currentQueueFlow.value.size == queueSize && PlayerManager.isPlayingFlow.value &&
                    fixture.service.controller.queue?.size == SessionQueueWindowSize
            }
            composeRule.runOnUiThread {
                assertTrue("必须向真实播放服务发送启动命令", AudioPlayerService.startSyncService(
                    composeRule.activity, "queue_bulk_removal_test", forceForeground = true
                ))
            }
            composeRule.waitUntil(UiTimeoutMs) {
                AudioPlayerService.isForegroundActiveForDiagnostics() &&
                    fixture.service.controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    fixture.service.updates.latestQueue?.map { it.description.title.toString() } ==
                    PlayerManager.currentQueueFlow.value.take(SessionQueueWindowSize).map { it.displayName() }
            }
            composeRule.runOnIdle {
                assertEquals(mismatch, LanguageManager.getCurrentLanguage(serviceContext))
                assertNotSame("真实服务仍需覆盖资源语言以覆盖 Context 创建路径", serviceContext,
                    LanguageManager.localizedContext(serviceContext, mismatch))
                assertTrue("会话必须包含普通专辑以覆盖本地专辑名称匹配",
                    fixture.service.controller.queue.orEmpty().any { it.description.description == FixtureAlbum })
            }
            fixture.block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { composeRule.runOnUiThread { fixture.heartbeat.stop(); fixture.visible = false } }
            cleanup { composeRule.waitForIdle() }
            cleanup { fixture.service.close() }
            cleanup { composeRule.runOnUiThread { PlayerManager.release() } }
            cleanup { runBlocking { removeFixtureStatistics(context, directory, songs) } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup {
                val editor = languagePreferences.edit()
                if (savedLanguage == null) editor.remove("selected_language")
                else editor.putString("selected_language", savedLanguage)
                check(editor.commit())
            }
            cleanup {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedPlatformLocales != null) {
                    val localeManager = context.getSystemService(LocaleManager::class.java)
                    if (localeManager != null && localeManager.applicationLocales != savedPlatformLocales) {
                        localeManager.applicationLocales = savedPlatformLocales
                    }
                }
            }
            cleanup { Locale.setDefault(savedDefaultLocale) }
            cleanup { check(directory.deleteRecursively()) }
            cleanupFailures.forEach { failure?.addSuppressed(it) }
            if (failure == null && cleanupFailures.isNotEmpty()) {
                throw AssertionError("批量删除回归测试清理失败", cleanupFailures.first())
            }
        }
    }

    private inner class PlaybackFixture(val context: Context) {
        val heartbeat = MainThreadHeartbeat()
        val service = PlaybackServiceClient(context)
        var visible by mutableStateOf(true)

        fun assertRemovalPerformance(queueSize: Int, removedCount: Int, startedAt: Long) {
            val elapsedMs = SystemClock.uptimeMillis() - startedAt
            Log.i("QueueBulkRemovalTest", "queue=$queueSize removed=$removedCount deleteElapsedMs=$elapsedMs " +
                "maximumMainThreadGapMs=${heartbeat.maximumDelayMs} queueCallbacks=${service.updates.queueChanges} " +
                "sessionQueueCallbackLatencyMs=${service.updates.lastCallbackAtMs - startedAt}")
            assertTrue("$queueSize 首队列批量删除耗时 ${elapsedMs}ms", elapsedMs < 4_000L)
            assertTrue("主线程最长未响应 ${heartbeat.maximumDelayMs}ms", heartbeat.maximumDelayMs < 2_000L)
        }

        fun assertSingleQueueCallback() {
            // 性能指标已在首次完成时记录，观察延迟回调不计入删除耗时
            composeRule.waitUntil(QueueCallbackObservationTimeoutMs) {
                service.updates.queueChanges > 1 ||
                    SystemClock.uptimeMillis() - service.updates.lastCallbackAtMs >= QueueCallbackStableMs
            }
            composeRule.runOnIdle {
                assertEquals("稳定观察后真实媒体会话只能发布一次删除结果", 1, service.updates.queueChanges)
            }
        }
    }

    private inner class PlaybackServiceClient(private val context: Context) {
        val updates = QueueUpdates()
        lateinit var controller: MediaController
            private set
        private var connection: ServiceConnection? = null
        private var ownsService = false

        fun bind() {
            val connected = CompletableFuture<MediaSession.Token>()
            val client = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    try {
                        // 复用现有车载绑定，不为测试扩大 runtime 模块可见性
                        val token = checkNotNull(binder).javaClass.getMethod("getSessionToken").invoke(binder)
                        connected.complete(token as? MediaSession.Token ?: error("播放服务没有媒体会话"))
                    } catch (error: Exception) {
                        connected.completeExceptionally(error)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    connected.completeExceptionally(AssertionError("播放服务绑定中断"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    connected.completeExceptionally(AssertionError("播放服务没有提供绑定接口"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    connected.completeExceptionally(AssertionError("播放服务绑定失效"))
                }
            }
            composeRule.runOnUiThread {
                val intent = Intent(context, AudioPlayerService::class.java).setAction(ActionBindCar)
                assertTrue("必须绑定真实播放服务", context.bindService(intent, client, Context.BIND_AUTO_CREATE))
                connection = client
                ownsService = true
            }
            controller = MediaController(context, connected.get(UiTimeoutMs, TimeUnit.MILLISECONDS))
            assertEquals(context.packageName, controller.packageName)
            composeRule.runOnUiThread {
                controller.registerCallback(updates, Handler(Looper.getMainLooper()))
            }
        }

        fun close() {
            composeRule.runOnUiThread {
                if (::controller.isInitialized) controller.unregisterCallback(updates)
                connection?.let(context::unbindService)
                connection = null
                if (ownsService) context.stopService(Intent(context, AudioPlayerService::class.java))
            }
            if (ownsService) {
                composeRule.waitUntil(UiTimeoutMs) {
                    !AudioPlayerService.isInstanceActiveForDiagnostics() && activeService() == null
                }
                ownsService = false
            }
        }
    }

    private class QueueUpdates : MediaController.Callback() {
        @Volatile var latestQueue: List<MediaSession.QueueItem>? = null
            private set
        @Volatile var queueChanges = 0
            private set
        @Volatile var lastCallbackAtMs = 0L
            private set

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            queueChanges += 1
            lastCallbackAtMs = SystemClock.uptimeMillis()
            latestQueue = queue?.toList()
        }

        fun reset() {
            latestQueue = null
            queueChanges = 0
            lastCallbackAtMs = 0L
        }
    }

    private fun activeService(): AudioPlayerService? = AudioPlayerService::class.java
        .getDeclaredField("activeServiceInstance").apply { isAccessible = true }.get(null) as? AudioPlayerService

    private fun createLocalQueue(directory: File, queueSize: Int): List<SongItem> {
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
        return List(queueSize) { index ->
            SongItem(
                id = -9_800_000L - index, name = "Queue bulk fixture $index", artist = "Synthetic fixture",
                album = FixtureAlbum, albumId = 0L, durationMs = 180_000L,
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
            lastTickMs = 0L
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
        const val SessionQueueWindowSize = 100
        const val FixtureAlbum = "Queue regression album"
        const val ActionBindCar = "moe.ouom.neriplayer.action.BIND_CAR"
        const val QueueCallbackStableMs = 250L
        const val QueueCallbackObservationTimeoutMs = 1_000L
    }
}
