package moe.ouom.neriplayer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testing.ManualInstrumentedTest
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ManualInstrumentedTest
class NowPlayingManualPerformanceReplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun compareExistingMiniPlayerWithNewPlaylistSong() {
        assumeTrue("需显式启用手动性能回放", InstrumentationRegistry.getArguments()
            .getString("runNowPlayingPerformanceReplay") == "true")
        assumeTrue("仅操作 API 33+ 模拟器", Build.VERSION.SDK_INT >= 33 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeComposeHostAvailable()
        assumeNoSyncTargets()
        val token = System.currentTimeMillis().toString()
        val fixtures = File(context.cacheDir, "now-playing-perf-fixture-$token")
        val output = File(checkNotNull(context.getExternalFilesDir(null)), "now-playing-perf-$token")
        check(fixtures.mkdirs() && output.mkdirs())
        val settings = SettingsRepository(context)
        val restoreSettings = runBlocking { saveSettings(settings) }
        val savedQueue = runBlocking { SavedQueue.read(context) }
        val promptKey = booleanPreferencesKey("preview_theme_prompt_answered")
        val densityKey = floatPreferencesKey("ui_density_scale")
        val warningKey = booleanPreferencesKey("acknowledged")
        val savedPrompt = runBlocking { context.dataStore.data.first()[promptKey] }
        val savedDensity = runBlocking { context.dataStore.data.first()[densityKey] }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first()[warningKey] }
        val originalFlags = automation.serviceInfo.flags
        val repository = LocalPlaylistRepository.getInstance(context)
        var playlistId: Long? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var songs = emptyList<SongItem>()
        var failure: Throwable? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                context.dataStore.edit { it[promptKey] = true }
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setDefaultStartDestination(Destinations.Library.route)
                settings.setUiStyle(UiStyle.Clean)
                settings.setDynamicColor(true)
                settings.setUiDensityScale(1f)
                settings.setLiquidGlassEnabled(true)
                settings.setAdvancedBlurEnabled(true)
                settings.setEnhancedAdvancedBlurEnabled(true)
                settings.setAdvancedBlurQuality(AdvancedBlurQuality.High)
                settings.setCoherentFeedbackEnabled(true)
                settings.setNowPlayingDynamicBackgroundEnabled(true)
                settings.setNowPlayingAudioReactiveEnabled(true)
                settings.setNowPlayingCoverBlurBackgroundEnabled(false)
                settings.setPreferWordTimedLyrics(true)
                settings.setDefaultLyricSource(LyricSourcePreference.Automatic)
                settings.setKeepPlaybackModeState(false)
                withTimeout(UiTimeoutMs) { check(repository.awaitInitialized()) }
            }
            songs = listOf(createSong(fixtures, "a", Color.RED), createSong(fixtures, "b", Color.CYAN))
            val playlist = runBlocking {
                repository.createPlaylistWithPreparedSongs("Perf-$token", songs)
            }
            playlistId = playlist.id
            songs = playlist.songs
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitUntil("created playlist '${playlist.name}' visible") { nativeNodes(playlist.name).isNotEmpty() }
            clickText(playlist.name)
            waitUntil("fixture songs visible after playlist click") { nativeNodes(songs[1].name).isNotEmpty() }
            scenario.onActivity {
                PlayerManager.release()
                PlayerManager.initialize(it.application)
                PlayerManager.playLocalPlaylist(playlist.id, songs, 0)
            }
            waitUntil("first fixture song playing") {
                PlayerManager.currentSongFlow.value?.id == songs[0].id && PlayerManager.isPlayingFlow.value
            }
            SystemClock.sleep(1_000L)
            measure(scenario, output, "existing-mini-player") { clickText(songs[0].name, bottommost = true) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("playlist visible after player back") { nativeNodes(songs[1].name).isNotEmpty() }
            SystemClock.sleep(700L)
            measure(scenario, output, "new-playlist-song") { clickText(songs[1].name) }
            waitUntil("second fixture song playing") {
                PlayerManager.currentSongFlow.value?.id == songs[1].id && PlayerManager.isPlayingFlow.value
            }
            Log.i(Tag, "diagnosticArtifacts=${output.absolutePath}; tracing perturbs timing; no performance acceptance asserted")
        } catch (error: Throwable) {
            failure = error
            UiFailureDiagnostics.record("now-playing-perf-replay", error)
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { scenario?.close() }
            cleanup { runBlocking { removeFixtureStatistics(fixtures, songs) } }
            cleanup { playlistId?.let { id -> runBlocking { restoreFixturePlaylistCount(id) } } }
            cleanup { playlistId?.let { id -> runBlocking { repository.deletePlaylist(id) }
                AppContainer.playlistUsageRepo.removeEntry(id, "local") } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { runBlocking { restoreSettings.forEach { it() } } }
            cleanup { runBlocking {
                context.dataStore.edit {
                    if (savedPrompt == null) it.remove(promptKey) else it[promptKey] = savedPrompt
                    if (savedDensity == null) it.remove(densityKey) else it[densityKey] = savedDensity
                }
                check(context.dataStore.data.first()[densityKey]?.toRawBits() == savedDensity?.toRawBits()) {
                    "应用内缩放偏好未完整恢复"
                }
                context.debugBuildWarningDataStore.edit {
                    if (savedWarning == null) it.remove(warningKey) else it[warningKey] = savedWarning
                }
            } }
            cleanup { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
            cleanup { check(fixtures.deleteRecursively()) { "合成媒体清理失败: $fixtures" } }
            cleanupFailures.forEach { failure?.addSuppressed(it) }
            if (failure == null && cleanupFailures.isNotEmpty()) {
                throw AssertionError("诊断清理失败", cleanupFailures.first())
            }
        }
    }

    private fun assumeNoSyncTargets() {
        val noTargets = runCatching {
            // 不打开 WebDavStorage，避免开库失败时的恢复流程改动已有账号
            val keysetKeys = setOf("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__")
            val webDavEmpty = context.getSharedPreferences("webdav_secure_prefs", Context.MODE_PRIVATE)
                .all.keys.all { it in keysetKeys }
            val github = SecureTokenStorage(context)
            webDavEmpty && github.isPersistent && github.getToken().isNullOrEmpty() &&
                github.getRepoOwner().isNullOrEmpty() && github.getRepoName().isNullOrEmpty()
        }.getOrDefault(false)
        assumeTrue("仅允许未配置 GitHub 或 WebDAV 同步账号和目标的专用模拟器", noTargets)
    }

    private fun scenarioWindow(scenario: ActivityScenario<MainActivity>): Window? {
        var window: Window? = null
        scenario.onActivity { window = it.window }
        return window
    }

    private fun measure(scenario: ActivityScenario<MainActivity>, directory: File, name: String, action: () -> Unit) {
        val recorder = FrameRecorder(checkNotNull(scenarioWindow(scenario)))
        val sampleMethods = InstrumentationRegistry.getArguments().getString("sampleMethods") != "false"
        var tracing = false
        try {
            recorder.start()
            if (sampleMethods) {
                Debug.startMethodTracingSampling(File(directory, "$name.trace").absolutePath, 32 * 1024 * 1024, 1_000)
                tracing = true
            }
            Log.i(Tag, "scenario=$name sampleMethods=$sampleMethods preActionUptimeMs=${SystemClock.uptimeMillis()}")
            action()
            // 使用真实时钟记录整个开场，不让 Compose 测试时钟跳过动画帧
            SystemClock.sleep(3_500L)
        } finally {
            try { if (tracing) Debug.stopMethodTracing() }
            finally { recorder.stop(File(directory, "$name-frames.csv")) }
        }
    }

    private inner class FrameRecorder(private val window: Window) {
        private val thread = HandlerThread("NeriPerfFrameMetrics")
        private val samples = ArrayList<LongArray>()
        private val metrics = intArrayOf(FrameMetrics.TOTAL_DURATION, FrameMetrics.DRAW_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.UNKNOWN_DELAY_DURATION)
        private val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
            val row = longArrayOf(SystemClock.elapsedRealtimeNanos(), dropped.toLong()) +
                LongArray(metrics.size) { frame.getMetric(metrics[it]) }
            synchronized(samples) { samples.add(row) }
        }

        fun start() {
            thread.start()
            instrumentation.runOnMainSync { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        }

        fun stop(file: File) {
            try {
                instrumentation.runOnMainSync { window.removeOnFrameMetricsAvailableListener(listener) }
                val drained = CountDownLatch(1)
                Handler(thread.looper).post { drained.countDown() }
                check(drained.await(2, TimeUnit.SECONDS)) { "FrameMetrics callback queue did not drain" }
                val rows = synchronized(samples) { samples.toList() }
                file.bufferedWriter().use { writer ->
                    writer.appendLine("callbackMonotonicNs,droppedReports,totalNs,drawNs,layoutNs,animationNs,syncNs,commandNs,swapNs,unknownNs")
                    rows.forEach { writer.appendLine(it.joinToString(",")) }
                }
                check(rows.isNotEmpty()) { "没有收到 FrameMetrics，不能生成性能对比" }
                val durations = rows.map { it[2] / 1_000_000.0 }.sorted()
                fun percentile(fraction: Double) = durations.getOrNull(((durations.size - 1) * fraction).toInt())
                Log.i(Tag, "${file.name}: frames=${rows.size} droppedReports=${rows.sumOf { it[1] }} " +
                    "p50Ms=${percentile(0.5)} p95Ms=${percentile(0.95)} maxMs=${durations.lastOrNull()}")
            } finally {
                thread.quitSafely()
                thread.join(2_000L)
            }
        }
    }

    private fun clickText(text: String, bottommost: Boolean = false) {
        val bounds = nativeNodes(text).map { node -> Rect().also(node::getBoundsInScreen) }
            .filter { !it.isEmpty }
        val target = checkNotNull(if (bottommost) bounds.maxByOrNull { it.bottom } else bounds.minByOrNull { it.width() * it.height() }) {
            "没有可点击的文字: $text"
        }
        val down = SystemClock.uptimeMillis()
        Log.i(Tag, "nativeClick text=$text bounds=$target downUptimeMs=$down")
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                target.exactCenterX(), target.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(48L)
        inject(MotionEvent.ACTION_UP)
    }

    private fun nativeNodes(text: String): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(pending::add)
        val found = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 2_000) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true)) found += node
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return found
    }

    private fun waitUntil(stage: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UiTimeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "真实 UI 状态等待超时: $stage" }
            SystemClock.sleep(50L)
        }
    }

    private fun createSong(directory: File, name: String, color: Int): SongItem {
        val audio = File(directory, "perf-$name.wav")
        val pcm = ByteArray(8_000 * 180 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
        }.array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        val lyric = (0 until 300).joinToString("\n") { index ->
            val start = index * 500
            "[$start,500]" + (0 until 10).joinToString("") { word -> "(${start + word * 50},50,0)词" }
        }
        File(directory, "perf-$name.lrc").writeText(lyric)
        val secondary = (0 until 300).joinToString("\n") { index ->
            val seconds = index / 2
            "[${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}.${if (index % 2 == 0) "00" else "50"}]Synthetic $name lyric $index"
        }
        File(directory, "perf-${name}_trans.lrc").writeText(secondary)
        File(directory, "perf-${name}_roma.lrc").writeText(secondary)
        val bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = android.graphics.Paint().apply { this.color = Color.YELLOW }
            canvas.drawCircle(384f, 384f, 180f, paint)
            File(directory, "perf-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        return LocalMediaSupport.toSongItem(LocalMediaSupport.inspect(context, Uri.fromFile(audio))).also {
            check(it.matchedLyric == lyric && !it.coverUrl.isNullOrBlank()) { "本地侧车夹具未被真实探测读取" }
        }
    }

    private suspend fun saveSettings(settings: SettingsRepository): List<suspend () -> Unit> {
        suspend fun <T> save(flow: Flow<T>, setter: suspend (T) -> Unit): suspend () -> Unit {
            val value = flow.first()
            return { setter(value) }
        }
        return listOf(
            save(settings.disclaimerAcceptedFlow.filterNotNull(), settings::setDisclaimerAccepted),
            save(settings.startupOnboardingCompletedFlow.filterNotNull(), settings::setStartupOnboardingCompleted),
            save(settings.defaultStartDestinationFlow, settings::setDefaultStartDestination),
            save(settings.uiStyleFlow, settings::setUiStyle),
            save(settings.dynamicColorFlow, settings::setDynamicColor),
            save(settings.liquidGlassEnabledFlow, settings::setLiquidGlassEnabled),
            save(settings.advancedBlurEnabledFlow, settings::setAdvancedBlurEnabled),
            save(settings.enhancedAdvancedBlurEnabledFlow, settings::setEnhancedAdvancedBlurEnabled),
            save(settings.advancedBlurQualityFlow, settings::setAdvancedBlurQuality),
            save(settings.coherentFeedbackEnabledFlow, settings::setCoherentFeedbackEnabled),
            save(settings.nowPlayingDynamicBackgroundEnabledFlow, settings::setNowPlayingDynamicBackgroundEnabled),
            save(settings.nowPlayingAudioReactiveEnabledFlow, settings::setNowPlayingAudioReactiveEnabled),
            save(settings.nowPlayingCoverBlurBackgroundEnabledFlow, settings::setNowPlayingCoverBlurBackgroundEnabled),
            save(settings.preferWordTimedLyricsFlow, settings::setPreferWordTimedLyrics),
            save(settings.defaultLyricSourceFlow, settings::setDefaultLyricSource),
            save(settings.keepPlaybackModeStateFlow, settings::setKeepPlaybackModeState)
        )
    }

    private suspend fun removeFixtureStatistics(directory: File, songs: List<SongItem>) {
        flushPlaybackStatsPendingWrites(context)
        val history = AppContainer.playHistoryRepo
        check(history.awaitHistoryLoaded())
        history.removeSongs(songs)
        withTimeout(UiTimeoutMs) { history.historyFlow.first { entries ->
            entries.none { it.localFilePath?.startsWith(directory.absolutePath) == true }
        } }
        val repository = AppContainer.playbackStatsRepo
        val keys = mutableSetOf<String>()
        var cursor: PlaybackStatsCursor? = null
        do {
            val page = repository.readPage(PlaybackStatsQuery(), after = cursor)
            page.tracks.filter { it.localFilePath?.startsWith(directory.absolutePath) == true }
                .mapTo(keys) { it.identityKey }
            cursor = page.nextCursor
        } while (cursor != null)
        repository.removeTracks(keys)
        withTimeout(UiTimeoutMs) {
            while (keys.any { repository.getStatForTrack(it) != null }) kotlinx.coroutines.delay(50L)
        }
    }

    private suspend fun restoreFixturePlaylistCount(playlistId: Long) {
        val repository = AppContainer.localPlaylistPlaybackStatsRepo
        check(repository.awaitInitialized()) { "歌单播放计数没有可信基线" }
        val mutableStats = checkNotNull(repository.statsFlow as? MutableStateFlow<List<LocalPlaylistPlaybackStat>>)
        val mutex = checkNotNull(repository.javaClass.getDeclaredField("mutex").apply {
            isAccessible = true
        }.get(repository) as? Mutex)
        val pendingChanges = repository.javaClass.getDeclaredField("pendingUiChanges").apply { isAccessible = true }
        // 仓库只公开合并接口，手动夹具在同一把锁内恢复自己的状态，再复用原有持久化与删除路径
        val restored = mutex.withLock {
            val kept = mutableStats.value.filterNot { it.playlistId == playlistId }
            mutableStats.value = kept
            pendingChanges.setBoolean(repository, true)
            kept
        }
        check(repository.awaitInitialized()) { "未能持久化夹具计数恢复" }
        check(repository.statsFlow.value == restored) { "夹具计数恢复影响了其它歌单统计" }
        check(repository.playCountFor(playlistId) == 0L) { "合成歌单播放计数仍存在" }
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
        const val Tag = "NowPlayingPerfReplay"
        const val UiTimeoutMs = 30_000L
    }
}
