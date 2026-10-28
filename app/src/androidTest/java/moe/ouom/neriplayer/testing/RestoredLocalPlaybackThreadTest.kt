package moe.ouom.neriplayer.testing

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoredLocalPlaybackThreadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun restoredRawAndFileSourcesKeepProgressUntilReadableWithoutMainThreadDiskProbes() {
        assumeTrue("仅在 API 35 模拟器运行", Build.VERSION.SDK_INT == 35 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("suspend 恢复必须从 instrumentation 线程调用", Looper.myLooper() != Looper.getMainLooper())
        val app = context.applicationContext as Application
        val preferences = readPlaybackPreferenceSnapshotSync(app)
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val directory = File(context.cacheDir, "restored-thread-${UUID.randomUUID()}")
        var failure: Throwable? = null
        try {
            check(directory.mkdirs())
            listOf(false, true).forEach { fileUri ->
                val audio = File(directory, if (fileUri) "file.wav" else "raw.wav")
                assertFalse(audio.exists())
                val reference = if (fileUri) Uri.fromFile(audio).toString() else audio.absolutePath
                val song = SongItem(
                    id = -System.nanoTime(), name = "Restored thread fixture", artist = "Offline fixture",
                    album = "Test", albumId = 0L, durationMs = 10_000L, coverUrl = null,
                    mediaUri = reference, localFilePath = audio.absolutePath.takeUnless { fileUri },
                    localFileName = audio.name, channelId = "local", audioId = audio.name
                )
                instrumentation.runOnMainSync {
                    PlayerManager.release()
                    PlayerManager.initializePreloaded(app, preferences.copy(
                        keepLastPlaybackProgress = true, maxCacheSizeBytes = 0L, usbExclusivePlayback = false
                    ), restored(song))
                }
                assertBootstrapWithoutDiskReads()
                assertNull(runBlocking { PlayerManager.resumeRestoredPlaybackIfNeeded() })
                instrumentation.runOnMainSync {
                    assertEquals(listOf(song), PlayerManager.currentQueueFlow.value)
                    assertEquals(song, PlayerManager.currentSongFlow.value)
                    assertEquals(PositionMs, PlayerManager.playbackPositionFlow.value)
                }
                writeWav(audio)
                val resumed = runBlocking { PlayerManager.resumeRestoredPlaybackIfNeeded() }
                instrumentation.runOnMainSync { PlayerManager.release() }
                assertEquals(PositionMs, resumed)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { instrumentation.runOnMainSync { PlayerManager.initializePreloaded(app, preferences) } }
            if (failure != null) failures.forEach(failure::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun serviceStartsStopAfterMissingRestoredSourcesWithoutDiscardingRetryState() {
        assumeTrue("仅在 API 35 模拟器运行", Build.VERSION.SDK_INT == 35 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("已有服务时不干预其生命周期", AudioPlayerService.isInstanceActiveForDiagnostics())
        assumeFalse("已有播放时不干预播放器", PlayerManager.isPlayingFlow.value ||
            PlayerManager.playWhenReadyFlow.value || PlayerManager.playbackControlPlayingFlow.value)
        assumeFalse("安全模式不创建播放服务", PlayerDependencies.presentation.shouldEnterSafeMode(context))
        val app = context.applicationContext as Application
        val preferences = readPlaybackPreferenceSnapshotSync(app)
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val directory = File(context.cacheDir, "restored-service-${UUID.randomUUID()}")
        var connection: ServiceConnection? = null
        var ownsService = false
        var failure: Throwable? = null
        try {
            check(directory.mkdirs())
            ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
                val starts = listOf(AudioPlayerService.ACTION_SYNC, null)
                    .flatMap { action -> listOf(false, true).map { fileUri -> action to fileUri } }
                starts.forEach { (action, fileUri) ->
                    val sourceName = if (action == null) "restart" else "app_bootstrap"
                    val audio = File(directory, "$sourceName-${if (fileUri) "file" else "raw"}.wav")
                    assertFalse(audio.exists())
                    val song = SongItem(
                        id = -System.nanoTime(), name = "Restored service fixture", artist = "Offline fixture",
                        album = "Test", albumId = 0L, durationMs = 10_000L, coverUrl = null,
                        mediaUri = if (fileUri) Uri.fromFile(audio).toString() else audio.absolutePath,
                        localFilePath = audio.absolutePath.takeUnless { fileUri }, localFileName = audio.name,
                        channelId = "local", audioId = audio.name
                    )
                    instrumentation.runOnMainSync {
                        assertNull("初始化快照前必须没有服务观察者", activeService())
                        PlayerManager.release()
                        PlayerManager.initializePreloaded(app, preferences.copy(
                            keepLastPlaybackProgress = true, maxCacheSizeBytes = 0L, usbExclusivePlayback = false
                        ), restored(song))
                        assertEquals(listOf(song), PlayerManager.currentQueueFlow.value)
                        assertEquals(song, PlayerManager.currentSongFlow.value)
                        assertEquals(PositionMs, PlayerManager.playbackPositionFlow.value)
                        assertTrue(PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch())
                    }
                    val connected = CompletableFuture<AudioPlayerService>()
                    val client = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            try {
                                val token = checkNotNull(binder).javaClass.getMethod("getSessionToken").invoke(binder)
                                checkNotNull(token) { "绑定必须提供可用的媒体会话" }
                                connected.complete(checkNotNull(activeService()))
                            } catch (error: Exception) {
                                connected.completeExceptionally(error)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            connected.completeExceptionally(AssertionError("播放服务绑定中断"))
                        }
                    }
                    instrumentation.runOnMainSync {
                        val intent = Intent(context, AudioPlayerService::class.java).setAction(ActionBindCar)
                        assertTrue(context.bindService(intent, client, Context.BIND_AUTO_CREATE))
                        connection = client
                        ownsService = true
                    }
                    val service = connected.get(ServiceTimeoutSeconds, TimeUnit.SECONDS)
                    awaitServiceState("绑定服务必须完成播放器初始化") { service.booleanField("playerRuntimeReady") }
                    var previousStartId = 0
                    var callbackGeneration = 0L
                    instrumentation.runOnMainSync {
                        assertEquals(listOf(song), PlayerManager.currentQueueFlow.value)
                        assertEquals(song, PlayerManager.currentSongFlow.value)
                        assertEquals(PositionMs, PlayerManager.playbackPositionFlow.value)
                        assertTrue(PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch())
                        previousStartId = service.field("latestStartId") as Int
                        callbackGeneration = service.field("mediaSessionCallback").field("requestGeneration") as Long
                    }
                    scenario.onActivity { activity ->
                        val intent = Intent(activity, AudioPlayerService::class.java).setAction(action)
                        if (action == AudioPlayerService.ACTION_SYNC) {
                            intent.putExtra(AudioPlayerService.EXTRA_START_SOURCE, "app_bootstrap")
                        }
                        assertNotNull(activity.startService(intent))
                    }
                    awaitServiceState("$sourceName 启动必须收到新的 AMS startId") {
                        val startId = service.field("latestStartId") as Int
                        startId > 0 && startId > previousStartId
                    }
                    awaitServiceState("缺源恢复必须停止前台并禁止 sticky 重启") {
                        !service.booleanField("hasReceivedStartCommand") &&
                            !service.booleanField("isForegroundStarted") &&
                            !AudioPlayerService.isForegroundActiveForDiagnostics() &&
                            service.booleanField("keepPlayerRuntimeAfterServiceStop") &&
                            !service.booleanField("allowServiceRestart")
                    }
                    instrumentation.runOnMainSync {
                        // 前台提升失败也会退出服务，不能把该路径误认为缺源恢复完成
                        assertEquals(callbackGeneration,
                            service.field("mediaSessionCallback").field("requestGeneration") as Long)
                        context.unbindService(client)
                        connection = null
                    }
                    awaitServiceState("已启动的服务解绑后必须真正销毁") { activeService() == null }
                    ownsService = false
                    instrumentation.runOnMainSync {
                        assertFalse(AudioPlayerService.isInstanceActiveForDiagnostics())
                        assertEquals(listOf(song), PlayerManager.currentQueueFlow.value)
                        assertEquals(song, PlayerManager.currentSongFlow.value)
                        assertEquals(PositionMs, PlayerManager.playbackPositionFlow.value)
                        assertTrue(PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch())
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
            cleanup {
                instrumentation.runOnMainSync {
                    connection?.let(context::unbindService)
                    connection = null
                    if (ownsService) context.stopService(Intent(context, AudioPlayerService::class.java))
                }
            }
            if (ownsService) cleanup { awaitServiceState("清理本次服务") { activeService() == null } }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { instrumentation.runOnMainSync { PlayerManager.initializePreloaded(app, preferences) } }
            if (failure != null) failures.forEach(failure::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun pausedLocalQueueSurvivesIdleServiceShutdownAndForegroundReturn() {
        assumeTrue("仅在 API 28 及以上模拟器运行", Build.VERSION.SDK_INT >= 28 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("已有服务时不干预其生命周期", AudioPlayerService.isInstanceActiveForDiagnostics())
        assumeFalse("已有播放时不干预播放器", PlayerManager.isPlayingFlow.value ||
            PlayerManager.playWhenReadyFlow.value || PlayerManager.playbackControlPlayingFlow.value)
        assumeFalse("安全模式不创建播放服务", PlayerDependencies.presentation.shouldEnterSafeMode(context))
        val app = context.applicationContext as Application
        val preferences = readPlaybackPreferenceSnapshotSync(app)
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val directory = File(context.cacheDir, "idle-return-service-${UUID.randomUUID()}")
        var ownsService = false
        var failure: Throwable? = null
        try {
            check(directory.mkdirs())
            val audio = File(directory, "paused.wav")
            writeWav(audio)
            val song = SongItem(
                id = -System.nanoTime(), name = "Idle return fixture", artist = "Offline fixture",
                album = "Test", albumId = 0L, durationMs = 10_000L, coverUrl = null,
                mediaUri = Uri.fromFile(audio).toString(), localFilePath = audio.absolutePath,
                localFileName = audio.name, channelId = "local", audioId = audio.name
            )
            fun assertPausedQueue() {
                assertTrue(PlayerManager.isPlayerInitialized())
                assertEquals(listOf(song), PlayerManager.currentQueueFlow.value)
                assertEquals(song, PlayerManager.currentSongFlow.value)
                assertEquals(PositionMs, PlayerManager.playbackPositionFlow.value)
                assertFalse(PlayerManager.isPlayingFlow.value)
                assertFalse(PlayerManager.playWhenReadyFlow.value)
                assertFalse(PlayerManager.playbackControlPlayingFlow.value)
            }
            fun startPausedService(): AudioPlayerService {
                // app_bootstrap 会合并最近的启动请求，等待窗口避免跳过真正的服务重建
                SystemClock.sleep(1_600L)
                instrumentation.runOnMainSync {
                    ownsService = AudioPlayerService.startSyncService(app, "app_bootstrap")
                    assertTrue("应用上下文必须能够启动前台服务", ownsService)
                }
                var service: AudioPlayerService? = null
                awaitServiceState("返回前台必须完成真实前台提升和运行时初始化") {
                    service = activeService()
                    service?.let {
                        it.booleanField("hasReceivedStartCommand") &&
                            it.booleanField("isForegroundStarted") &&
                            it.booleanField("playerRuntimeReady") &&
                            AudioPlayerService.isForegroundActiveForDiagnostics()
                    } == true
                }
                val notifications = checkNotNull(app.getSystemService(NotificationManager::class.java))
                if (notifications.areNotificationsEnabled()) {
                    awaitServiceState("前台播放服务必须发布系统前台通知") {
                        notifications.activeNotifications.any {
                            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                        }
                    }
                }
                instrumentation.runOnMainSync { assertPausedQueue() }
                return checkNotNull(service)
            }
            instrumentation.runOnMainSync {
                PlayerManager.release()
                PlayerManager.initializePreloaded(app, preferences.copy(
                    keepLastPlaybackProgress = true, maxCacheSizeBytes = 0L, usbExclusivePlayback = false
                ), restored(song).copy(shouldResumePlayback = false))
                assertPausedQueue()
            }
            ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
                var service = startPausedService()
                repeat(2) {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    val stoppedService = service
                    instrumentation.runOnMainSync {
                        assertPausedQueue()
                        val eligible = AudioPlayerService::class.java
                            .getDeclaredMethod("isEligibleForIdleShutdown").apply { isAccessible = true }
                            .invoke(stoppedService) as Boolean
                        assertTrue("暂停服务必须符合实际闲置回收条件", eligible)
                        val startId = stoppedService.field("latestStartId") as Int
                        assertTrue(startId > 0)
                        // 直接触发实际闲置回收入口，避免设备测试等待数分钟
                        AudioPlayerService::class.java
                            .getDeclaredMethod("stopIdlePlaybackService", Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(stoppedService, startId)
                    }
                    awaitServiceState("后台闲置回收必须真正销毁服务") {
                        activeService() == null && !AudioPlayerService.isInstanceActiveForDiagnostics() &&
                            !AudioPlayerService.isForegroundActiveForDiagnostics()
                    }
                    ownsService = false
                    instrumentation.runOnMainSync {
                        assertTrue(stoppedService.booleanField("keepPlayerRuntimeAfterServiceStop"))
                        assertFalse(stoppedService.booleanField("playerRuntimeReady"))
                        assertPausedQueue()
                    }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    service = startPausedService()
                    assertTrue("返回前台必须创建新的服务实例", service !== stoppedService)
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
            cleanup {
                instrumentation.runOnMainSync {
                    if (ownsService) context.stopService(Intent(context, AudioPlayerService::class.java))
                }
            }
            if (ownsService) cleanup { awaitServiceState("清理本次服务") { activeService() == null } }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { instrumentation.runOnMainSync { PlayerManager.initializePreloaded(app, preferences) } }
            if (failure != null) failures.forEach(failure::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun warmEmptyPlayerAcceptsInitialPlayCommandBeforeEmptyCollectorsCanStopService() {
        assumeTrue("仅在 API 28 及以上模拟器运行", Build.VERSION.SDK_INT >= 28 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("已有服务时不干预其生命周期", AudioPlayerService.isInstanceActiveForDiagnostics())
        assumeFalse("已有播放时不干预播放器", PlayerManager.isPlayingFlow.value ||
            PlayerManager.playWhenReadyFlow.value || PlayerManager.playbackControlPlayingFlow.value)
        assumeFalse("安全模式不创建播放服务", PlayerDependencies.presentation.shouldEnterSafeMode(context))
        val app = context.applicationContext as Application
        val preferences = readPlaybackPreferenceSnapshotSync(app)
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val directory = File(context.cacheDir, "warm-empty-play-${UUID.randomUUID()}")
        var ownsService = false
        var failure: Throwable? = null
        try {
            check(directory.mkdirs())
            val audio = File(directory, "first-play.wav")
            writeWav(audio)
            val song = SongItem(
                id = -System.nanoTime(), name = "Warm empty play fixture", artist = "Offline fixture",
                album = "Test", albumId = 0L, durationMs = 10_000L, coverUrl = null,
                mediaUri = Uri.fromFile(audio).toString(), localFilePath = audio.absolutePath,
                localFileName = audio.name, channelId = "local", audioId = audio.name
            )
            instrumentation.runOnMainSync {
                PlayerManager.release()
                PlayerManager.initializePreloaded(app, preferences.copy(
                    keepLastPlaybackProgress = false, maxCacheSizeBytes = 0L, usbExclusivePlayback = false
                ), RestoredPlayerStateSnapshot(
                    playlist = emptyList(), currentIndex = -1, currentMediaUrl = null,
                    repeatMode = 0, shuffleEnabled = false, shuffleRestorePlaylist = null,
                    shuffleRestoreIndex = -1, resumePositionMs = 0L, shouldResumePlayback = false,
                    originalPlaylistSize = 0, persistedIndex = -1
                ))
                assertTrue(PlayerManager.isPlayerInitialized())
                assertFalse(PlayerManager.hasItems())
                assertNull(PlayerManager.currentSongFlow.value)
                assertNull(activeService())
            }
            ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                instrumentation.runOnMainSync {
                    val intent = Intent(app, AudioPlayerService::class.java)
                        .setAction(AudioPlayerService.ACTION_PLAY)
                        .putParcelableArrayListExtra("playlist", arrayListOf(song))
                        .putExtra("index", 0)
                        .putExtra(AudioPlayerService.EXTRA_START_SOURCE, "warm_empty_play_test")
                    assertNotNull(ContextCompat.startForegroundService(app, intent))
                    ownsService = true
                }
                var service: AudioPlayerService? = null
                awaitServiceState("空队列初始化观察者不能抢在首个播放命令前停止服务") {
                    service = activeService()
                    service?.let {
                        it.booleanField("hasReceivedStartCommand") &&
                            it.booleanField("isForegroundStarted") &&
                            it.booleanField("playerRuntimeReady") &&
                            AudioPlayerService.isForegroundActiveForDiagnostics() &&
                            PlayerManager.currentSongFlow.value?.id == song.id &&
                            PlayerManager.isPlayingFlow.value && PlayerManager.playbackPositionFlow.value > 0L
                    } == true
                }
                instrumentation.runOnMainSync {
                    assertEquals(listOf(song.id), PlayerManager.currentQueueFlow.value.map { it.id })
                    PlayerManager.pause()
                }
                awaitServiceState("本地播放暂停后必须保留首个命令建立的队列") {
                    activeService() === service && !PlayerManager.isPlayingFlow.value &&
                        !PlayerManager.playWhenReadyFlow.value && !PlayerManager.playbackControlPlayingFlow.value
                }
                instrumentation.runOnMainSync {
                    assertEquals(listOf(song.id), PlayerManager.currentQueueFlow.value.map { it.id })
                    assertEquals(song.id, PlayerManager.currentSongFlow.value?.id)
                    assertTrue(checkNotNull(service).booleanField("playerRuntimeReady"))
                    assertTrue(AudioPlayerService.isForegroundActiveForDiagnostics())
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
            cleanup {
                instrumentation.runOnMainSync {
                    if (ownsService) context.stopService(Intent(context, AudioPlayerService::class.java))
                }
            }
            if (ownsService) cleanup { awaitServiceState("清理本次服务") { activeService() == null } }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { instrumentation.runOnMainSync { PlayerManager.initializePreloaded(app, preferences) } }
            if (failure != null) failures.forEach(failure::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun activeService(): AudioPlayerService? = AudioPlayerService::class.java
        .getDeclaredField("activeServiceInstance").apply { isAccessible = true }.get(null) as? AudioPlayerService

    private fun Any.field(name: String): Any = javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(this) ?: error("字段为空: $name")

    private fun AudioPlayerService.booleanField(name: String): Boolean = field(name) as Boolean

    private fun awaitServiceState(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(ServiceTimeoutSeconds)
        do {
            var matched = false
            instrumentation.runOnMainSync { matched = predicate() }
            if (matched) return
            Thread.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }

    private fun restored(song: SongItem) = RestoredPlayerStateSnapshot(
        playlist = listOf(song), currentIndex = 0, currentMediaUrl = null,
        repeatMode = 0, shuffleEnabled = false, shuffleRestorePlaylist = null, shuffleRestoreIndex = -1,
        resumePositionMs = PositionMs, shouldResumePlayback = true, originalPlaylistSize = 1, persistedIndex = 0
    )

    private fun assertBootstrapWithoutDiskReads() {
        val violations = ConcurrentLinkedQueue<String>()
        instrumentation.runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            try {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads()
                    .penaltyListener({ it.run() }) { violation -> violations.add(violation.javaClass.name) }
                    .build())
                assertTrue(PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch())
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        instrumentation.waitForIdleSync()
        assertTrue("bootstrap 主线程磁盘探测: $violations", violations.isEmpty())
    }

    private fun writeWav(audio: File) {
        val pcm = ByteArray(8_000 * 10 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
    }

    private data class SavedPlaybackQueue(
        val roomPrimary: Boolean,
        val state: PersistedState?,
        val legacyFiles: Map<String, ByteArray?>
    ) {
        suspend fun restore(context: Context) {
            val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
            if (roomPrimary && state != null) store.replaceSnapshot(state) else store.clear()
            legacyFiles.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes != null) file.writeBytes(bytes) else check(!file.exists() || file.delete())
            }
            if (!roomPrimary) store.markLegacyJsonPrimary(System.currentTimeMillis())
        }

        companion object {
            suspend fun read(context: Context): SavedPlaybackQueue {
                val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
                return SavedPlaybackQueue(store.isRoomPrimary(), store.readIfRoomPrimary(),
                    QueueFiles.associateWith { name -> File(context.filesDir, name).takeIf(File::exists)?.readBytes() })
            }
        }
    }

    private companion object {
        const val PositionMs = 1_500L
        const val ServiceTimeoutSeconds = 25L
        const val ActionBindCar = "moe.ouom.neriplayer.action.BIND_CAR"
        val QueueFiles = listOf("last_playlist.json", "last_playback_state.json")
    }
}
