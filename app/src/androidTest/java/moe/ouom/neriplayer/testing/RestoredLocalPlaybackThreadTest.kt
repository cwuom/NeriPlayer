package moe.ouom.neriplayer.testing

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongRestoreSelection
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.NowPlayingSongEditOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.PlayerManagerNowPlayingSongEditPlaybackPort
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveEditSongBaselineFromSong
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
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

    @Test
    fun singleRestoreWhilePlayingPublishesOriginalMetadataToSessionAndNotification() {
        listOf(false, true).forEach(::verifySingleRestoreWhilePlaying)
    }

    @Test
    fun titleAndArtistOnlyEditsWhilePausedReachSessionAndNotificationAfterResuming() {
        verifySingleRestoreWhilePlaying(notificationLyricsEnabled = true, metadataOnly = true)
    }

    private fun verifySingleRestoreWhilePlaying(notificationLyricsEnabled: Boolean) =
        verifySingleRestoreWhilePlaying(notificationLyricsEnabled, metadataOnly = false)

    private fun verifySingleRestoreWhilePlaying(notificationLyricsEnabled: Boolean, metadataOnly: Boolean) {
        assumeTrue("仅在 API 37 模拟器运行", Build.VERSION.SDK_INT == 37 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("已有服务时不干预其生命周期", AudioPlayerService.isInstanceActiveForDiagnostics())
        assumeFalse("已有播放时不干预播放器", PlayerManager.isPlayingFlow.value ||
            PlayerManager.playWhenReadyFlow.value || PlayerManager.playbackControlPlayingFlow.value)
        assumeFalse("已有睡眠定时器时不干预播放", PlayerManager.sleepTimerManager.timerState.value.isActive)
        assumeFalse("安全模式不创建播放服务", PlayerDependencies.presentation.shouldEnterSafeMode(context))
        assumeNoFixtureSyncTargets()
        val notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))
        assumeTrue("必须允许真实媒体通知", notifications.areNotificationsEnabled())
        assumeTrue("媒体通知渠道不能被关闭", notifications.getNotificationChannel(PlaybackNotificationChannel)
            ?.importance != NotificationManager.IMPORTANCE_NONE)
        val app = context.applicationContext as Application
        val preferences = readPlaybackPreferenceSnapshotSync(app)
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val settings = AppContainer.settingsRepo
        val savedLyricSettings = runBlocking { NotificationLyricSettingsSnapshot(
            settings.dynamicIslandLyricsEnabledFlow.first(), settings.externalBluetoothLyricsEnabledFlow.first(),
            settings.externalBluetoothTranslationEnabledFlow.first(), settings.colorOsLockscreenLyricsEnabledFlow.first()
        ) }
        val directory = File(context.cacheDir, "playing-metadata-restore-${UUID.randomUUID()}")
        val fixtureSongs = mutableListOf<SongItem>()
        val extractedCovers = mutableSetOf<File>()
        var connection: ServiceConnection? = null
        var ownsService = false
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync { PlayerManager.release() }
            runBlocking {
                settings.setDynamicIslandLyricsEnabled(notificationLyricsEnabled)
                settings.setExternalBluetoothLyricsEnabled(notificationLyricsEnabled)
                settings.setExternalBluetoothTranslationEnabled(notificationLyricsEnabled)
                settings.setColorOsLockscreenLyricsEnabled(false)
            }
            check(directory.mkdirs())
            val audio = File(directory, "synthetic-playing-restore.flac")
            instrumentation.context.assets.open("metadata/synthetic-playing-restore.flac").use { input ->
                audio.outputStream().use { output -> input.copyTo(output) }
            }
            val cover = File(directory, "edited-cover.png")
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
                try {
                    bitmap.eraseColor(Color.rgb(227, 43, 70))
                    cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
            }
            val originalTranslation = if (notificationLyricsEnabled && !metadataOnly) "" else OriginalTranslation
            val original = SongItem(
                id = -System.nanoTime(), name = OriginalTitle, artist = OriginalArtist,
                album = "Synthetic restore fixture", albumId = 0L, durationMs = 30_000L, coverUrl = null,
                mediaUri = Uri.fromFile(audio).toString(), localFilePath = audio.path, localFileName = audio.name,
                channelId = "local", audioId = audio.name, originalName = OriginalTitle,
                originalArtist = OriginalArtist, originalCoverUrl = "", matchedLyric = OriginalLyric,
                matchedTranslatedLyric = originalTranslation, matchedRomanizedLyric = OriginalRomanization,
                originalLyric = OriginalLyric, originalTranslatedLyric = originalTranslation,
                originalRomanizedLyric = OriginalRomanization, lyricSyncEdited = false
            )
            fixtureSongs += original
            if (notificationLyricsEnabled) {
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, runBlocking {
                    LocalMediaSupport.writeEditableMetadata(app, original, writeLyrics = true)
                })
            }
            val embedded = checkNotNull(LocalMediaSupport.inspectEmbeddedLyrics(app, original))
            assertTrue(embedded.embeddedSourceResolved)
            assertEquals(OriginalLyric, embedded.lyric)
            assertEquals(originalTranslation, embedded.translatedLyric.orEmpty())
            assertEquals(OriginalRomanization, embedded.romanizedLyric)
            instrumentation.runOnMainSync {
                PlayerManager.initializePreloaded(app, preferences.copy(
                    keepLastPlaybackProgress = false, maxCacheSizeBytes = 0L, usbExclusivePlayback = false,
                    playbackFadeIn = false, playbackCrossfadeNext = false, playbackSpeed = 1f,
                    amllLyricsEnabled = false
                ), restored(original).copy(resumePositionMs = 0L, shouldResumePlayback = false, repeatMode = 1))
            }
            ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val intent = Intent(activity, AudioPlayerService::class.java)
                        .setAction(AudioPlayerService.ACTION_PLAY)
                        .putParcelableArrayListExtra("playlist", arrayListOf(original))
                        .putExtra("index", 0)
                        .putExtra(AudioPlayerService.EXTRA_START_SOURCE, "playing_metadata_restore_test")
                    assertNotNull(ContextCompat.startForegroundService(activity, intent))
                    ownsService = true
                }
                awaitServiceState("合成 FLAC 必须进入真实播放") {
                    AudioPlayerService.isForegroundActiveForDiagnostics() &&
                        PlayerManager.currentSongFlow.value?.id == original.id &&
                        PlayerManager.isPlayingFlow.value && PlayerManager.playbackPositionFlow.value > 0L
                }
                val token = CompletableFuture<MediaSession.Token>()
                val client = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        try {
                            token.complete(checkNotNull(binder).javaClass.getMethod("getSessionToken")
                                .invoke(binder) as? MediaSession.Token ?: error("播放服务没有媒体会话"))
                        } catch (error: Exception) {
                            token.completeExceptionally(error)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        token.completeExceptionally(AssertionError("播放服务绑定中断"))
                    }
                }
                instrumentation.runOnMainSync {
                    assertTrue(context.bindService(Intent(context, AudioPlayerService::class.java)
                        .setAction(ActionBindCar), client, Context.BIND_AUTO_CREATE))
                    connection = client
                }
                val controller = MediaController(context, token.get(ServiceTimeoutSeconds, TimeUnit.SECONDS))
                assertEquals(context.packageName, controller.packageName)
                listOf(false, true).forEachIndexed { round, writeLocalMetadata ->
                    instrumentation.runOnMainSync { PlayerManager.seekTo(0L) }
                    val editedTitle = "Edited title $round"
                    val editedArtist = "Edited artist $round"
                    val editedLyrics = if (metadataOnly) listOf(OriginalLyric, originalTranslation, OriginalRomanization)
                    else listOf("[00:01.00]edited lyric $round",
                        if (notificationLyricsEnabled) "" else "[00:01.00]edited translation $round",
                        "[00:01.00]edited romanization $round")
                    if (metadataOnly) {
                        instrumentation.runOnMainSync { PlayerManager.pause(forcePersist = false) }
                        awaitServiceState("仅编辑名称前必须暂停") { !PlayerManager.isPlayingFlow.value }
                        saveFixtureNamesOnce(scenario, checkNotNull(PlayerManager.currentSongFlow.value),
                            editedTitle, editedArtist, writeLocalMetadata)
                        instrumentation.runOnMainSync { PlayerManager.play() }
                    } else runBlocking {
                        assertTrue(PlayerManager.updateSongLyricsAndTranslation(
                            songToUpdate = checkNotNull(PlayerManager.currentSongFlow.value),
                            newLyrics = editedLyrics[0], newTranslatedLyrics = editedLyrics[1],
                            newRomanizedLyrics = editedLyrics[2], writeLocalMetadata = true
                        ))
                        assertTrue(PlayerManager.updateSongCustomInfo(
                            originalSong = checkNotNull(PlayerManager.currentSongFlow.value),
                            customCoverUrl = Uri.fromFile(cover).toString(), customName = editedTitle,
                            customArtist = editedArtist, writeLocalMetadata = true, writeLyrics = true
                        ))
                    }
                    awaitPlayingMetadata("通知歌词=$notificationLyricsEnabled，第 $round 轮修改必须到达当前歌曲、媒体会话和系统通知",
                        controller, notifications, original.id, editedTitle, editedArtist, editedLyrics,
                        hasArtwork = !metadataOnly, notificationLyricsEnabled)
                    LocalMediaSupport.peekCachedEmbeddedCoverUri(app, original)?.let { reference ->
                        Uri.parse(reference).path?.let { extractedCovers += File(it) }
                    }
                    val edited = checkNotNull(PlayerManager.currentSongFlow.value)
                    restorePlayingFixtureOnce(scenario, edited, writeLocalMetadata)
                    val originalLyrics = listOf(OriginalLyric, originalTranslation, OriginalRomanization)
                    awaitPlayingMetadata("通知歌词=$notificationLyricsEnabled，第 $round 轮一次恢复保存必须刷新全部真实播放展示",
                        controller, notifications, original.id, OriginalTitle, OriginalArtist, originalLyrics,
                        hasArtwork = false, notificationLyricsEnabled)
                    val stableUntil = SystemClock.elapsedRealtime() + 1_500L
                    while (SystemClock.elapsedRealtime() < stableUntil) {
                        instrumentation.runOnMainSync {
                            if (!playingMetadataMatches(controller, notifications, original.id, OriginalTitle,
                                OriginalArtist, originalLyrics, hasArtwork = false, notificationLyricsEnabled)) {
                                throw AssertionError("恢复值不能被延迟的旧快照重新覆盖\n" + playingMetadataDiagnostics(
                                    controller, notifications, original.id, OriginalTitle, OriginalArtist,
                                    originalLyrics, hasArtwork = false, notificationLyricsEnabled))
                            }
                            val restored = checkNotNull(PlayerManager.currentSongFlow.value)
                            assertNull(restored.customName)
                            assertNull(restored.customArtist)
                            assertEquals("", restored.originalCoverUrl)
                            assertEquals(OriginalLyric, restored.originalLyric)
                            assertEquals(originalTranslation, restored.originalTranslatedLyric)
                            assertEquals(OriginalRomanization, restored.originalRomanizedLyric)
                        }
                        Thread.sleep(25L)
                    }
                    val physical = checkNotNull(LocalMediaSupport.inspectEmbeddedLyrics(app, original))
                    assertEquals(if (writeLocalMetadata) originalLyrics else editedLyrics,
                        listOf(physical.lyric, physical.translatedLyric.orEmpty(), physical.romanizedLyric))
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
                if (ownsService) {
                    instrumentation.runOnMainSync { PlayerManager.pause(forcePersist = true) }
                    awaitServiceState("清理前停止本次合成音频播放") {
                        !PlayerManager.isPlayingFlow.value && !PlayerManager.playWhenReadyFlow.value
                    }
                }
            }
            cleanup {
                instrumentation.runOnMainSync {
                    connection?.let(context::unbindService)
                    connection = null
                    if (ownsService) context.stopService(Intent(context, AudioPlayerService::class.java))
                }
            }
            if (ownsService) cleanup { awaitServiceState("清理本次元数据验收服务") { activeService() == null } }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { removePlayingFixtureStatistics(directory, fixtureSongs) } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { runBlocking {
                settings.setDynamicIslandLyricsEnabled(savedLyricSettings.dynamicIsland)
                settings.setExternalBluetoothLyricsEnabled(savedLyricSettings.externalLyrics)
                settings.setExternalBluetoothTranslationEnabled(savedLyricSettings.externalTranslation)
                settings.setColorOsLockscreenLyricsEnabled(savedLyricSettings.colorOs)
            } }
            cleanup {
                val coverDirectory = File(context.filesDir, "local_audio_covers").canonicalFile
                extractedCovers.filter { it.canonicalFile.parentFile == coverDirectory }.forEach {
                    check(!it.exists() || it.delete())
                }
                fixtureSongs.forEach(LocalMediaSupport::invalidateSongAssetCaches)
                check(!directory.exists() || directory.deleteRecursively())
            }
            cleanup { instrumentation.runOnMainSync { PlayerManager.initializePreloaded(app, preferences) } }
            if (failure != null) failures.forEach(failure::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun saveFixtureNamesOnce(
        scenario: ActivityScenario<TestComposeActivity>, song: SongItem,
        title: String, artist: String, writeLocalMetadata: Boolean
    ) {
        lateinit var owner: NowPlayingSongEditOwner
        var saved = 0
        scenario.onActivity { activity ->
            owner = NowPlayingSongEditOwner(song, song.customCoverUrl ?: song.coverUrl.orEmpty(),
                resolveEditSongBaselineFromSong(song, null, null, null, null), activity.lifecycleScope, {},
                PlayerManagerNowPlayingSongEditPlaybackPort)
            owner.updateTitle(title)
            owner.updateArtist(artist)
            owner.saveEditedSongInfo(song, ViewModelProvider(activity)[NowPlayingViewModel::class.java],
                writeLocalMetadata, SnackbarHostState(), activity.resources, onSaved = { saved++ })
        }
        try {
            awaitServiceState("仅改歌名和歌手的一次保存必须成功") { saved == 1 }
        } finally {
            instrumentation.runOnMainSync { owner.dispose() }
        }
        assertEquals(1, saved)
    }

    private fun restorePlayingFixtureOnce(
        scenario: ActivityScenario<TestComposeActivity>, song: SongItem, writeLocalMetadata: Boolean
    ) {
        lateinit var owner: NowPlayingSongEditOwner
        var saved = 0
        scenario.onActivity { activity ->
            owner = NowPlayingSongEditOwner(
                initialSong = song, initialCoverUrl = song.customCoverUrl ?: song.coverUrl.orEmpty(),
                initialBaseline = resolveEditSongBaselineFromSong(song, null, null, null, null),
                scope = activity.lifecycleScope, onSavingChanged = {},
                playbackPort = PlayerManagerNowPlayingSongEditPlaybackPort
            )
            owner.restoreOriginalInfo(context, song, ViewModelProvider(activity)[NowPlayingViewModel::class.java],
                EditSongRestoreSelection(cover = true, title = true, artist = true, lyrics = true))
        }
        try {
            awaitServiceState("一次恢复选择必须完成") { !owner.isOriginalInfoRestoringState.value }
            scenario.onActivity { activity ->
                owner.saveEditedSongInfo(song, ViewModelProvider(activity)[NowPlayingViewModel::class.java],
                    writeLocalMetadata, SnackbarHostState(), activity.resources, onSaved = { saved++ })
            }
            awaitServiceState("一次恢复保存必须成功，不能依赖再次点击恢复") { saved == 1 }
        } finally {
            instrumentation.runOnMainSync { owner.dispose() }
        }
        assertEquals(1, saved)
    }

    private fun playingMetadataMatches(
        controller: MediaController, notifications: NotificationManager, songId: Long,
        title: String, artist: String, lyrics: List<String>, hasArtwork: Boolean,
        notificationLyricsEnabled: Boolean
    ): Boolean {
        val song = PlayerManager.currentSongFlow.value ?: return false
        val metadata = controller.metadata ?: return false
        val notification = notifications.activeNotifications.singleOrNull {
            it.notification.category == Notification.CATEGORY_TRANSPORT &&
                BundleCompat.getParcelable(it.notification.extras, Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java) == controller.sessionToken
        }?.notification ?: return false
        val sessionText = currentPlayingSessionText(song) ?: return false
        if (sessionText.forceLyrics != notificationLyricsEnabled || sessionText.colorOs) return false
        if (notificationLyricsEnabled) {
            val expectedLine = lyrics.first().substringAfter("]")
            val expectedTranslation = lyrics[1].takeIf(String::isNotEmpty)?.substringAfter("]")
            val expectedArtist = expectedTranslation ?: "$title - $artist"
            if (sessionText.lyric != expectedLine || sessionText.translation.orEmpty() != expectedTranslation.orEmpty() ||
                sessionText.title != expectedLine || sessionText.artist != expectedArtist
            ) return false
            if (expectedTranslation != null &&
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION) != "$title - $artist"
            ) return false
        } else if (sessionText.title != title || sessionText.artist != artist) return false
        return song.id == songId && song.displayName() == title && song.displayArtist() == artist &&
            listOf(song.matchedLyric, song.matchedTranslatedLyric, song.matchedRomanizedLyric) == lyrics &&
            (hasArtwork || song.coverUrl == null && song.customCoverUrl == null) &&
            PlayerManager.isPlayingFlow.value && controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == sessionText.title &&
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) == sessionText.artist &&
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) == sessionText.displayTitle &&
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == sessionText.displaySubtitle &&
            (metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null) == hasArtwork &&
            (metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null) == hasArtwork &&
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title &&
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == artist &&
            (notification.getLargeIcon() != null) == hasArtwork
    }

    private fun awaitPlayingMetadata(
        message: String, controller: MediaController, notifications: NotificationManager, songId: Long,
        title: String, artist: String, lyrics: List<String>, hasArtwork: Boolean,
        notificationLyricsEnabled: Boolean
    ) {
        try {
            awaitServiceState(message) {
                playingMetadataMatches(controller, notifications, songId, title, artist, lyrics,
                    hasArtwork, notificationLyricsEnabled)
            }
        } catch (failure: AssertionError) {
            var details = ""
            instrumentation.runOnMainSync {
                details = playingMetadataDiagnostics(controller, notifications, songId, title, artist,
                    lyrics, hasArtwork, notificationLyricsEnabled)
            }
            throw AssertionError("$message\n$details", failure)
        }
    }

    private fun playingMetadataDiagnostics(
        controller: MediaController, notifications: NotificationManager, songId: Long,
        title: String, artist: String, lyrics: List<String>, hasArtwork: Boolean,
        notificationLyricsEnabled: Boolean
    ): String = buildString {
        appendLine("expected=" + mapOf("songId" to songId, "title" to title, "artist" to artist,
            "lyrics" to lyrics, "artwork" to hasArtwork, "notificationLyrics" to notificationLyricsEnabled))
        fun capture(label: String, read: () -> Any?) {
            appendLine("$label=" + runCatching(read).getOrElse { "${it.javaClass.simpleName}: ${it.message}" })
        }
        fun bitmap(bitmap: Bitmap?): String? = bitmap?.let { "${it.width}x${it.height},recycled=${it.isRecycled}" }
        val song = PlayerManager.currentSongFlow.value
        if (song?.id != songId) {
            appendLine("currentFlow=" + if (song == null) "null" else "unrelated song, content redacted")
            return@buildString
        }
        capture("currentFlow") { mapOf("name" to song.name, "artist" to song.artist,
            "displayName" to song.displayName(), "displayArtist" to song.displayArtist(),
            "cover" to song.coverUrl, "customCover" to song.customCoverUrl,
            "customName" to song.customName, "customArtist" to song.customArtist,
            "lyrics" to listOf(song.matchedLyric, song.matchedTranslatedLyric, song.matchedRomanizedLyric),
            "originalLyrics" to listOf(song.originalLyric, song.originalTranslatedLyric, song.originalRomanizedLyric),
            "lyricSyncEdited" to song.lyricSyncEdited) }
        capture("player") { mapOf("isPlaying" to PlayerManager.isPlayingFlow.value,
            "playWhenReady" to PlayerManager.playWhenReadyFlow.value,
            "controlPlaying" to PlayerManager.playbackControlPlayingFlow.value,
            "positionMs" to PlayerManager.playbackPositionFlow.value) }
        capture("source") { currentPlayingSessionText(song) }
        capture("lyricSwitches") { listOf("dynamicIslandLyricsEnabled", "externalBluetoothLyricsEnabled",
            "externalBluetoothTranslationEnabled").associateWith { PlayerManager.field(it) } }
        capture("sessionToken") { controller.sessionToken }
        capture("sessionPlayback") { controller.playbackState?.let { mapOf("state" to it.state,
            "positionMs" to it.position, "speed" to it.playbackSpeed, "error" to it.errorMessage) } }
        capture("sessionText") { controller.metadata?.let { metadata -> listOf(
            MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, MediaMetadata.METADATA_KEY_MEDIA_ID,
            MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        ).associateWith(metadata::getString) } }
        capture("sessionBitmaps") { controller.metadata?.let { metadata -> listOf(
            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        ).associateWith { bitmap(metadata.getBitmap(it)) } } }
        capture("notifications") { notifications.activeNotifications.filter { posted ->
            val value = posted.notification
            val token = BundleCompat.getParcelable(value.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            val notificationTitle = value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            value.category == Notification.CATEGORY_TRANSPORT && (token == controller.sessionToken ||
                notificationTitle in setOf(OriginalTitle, "Edited title 0", "Edited title 1"))
        }.map { posted ->
            val value = posted.notification
            val token = BundleCompat.getParcelable(value.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            mapOf("id" to posted.id, "sessionToken" to token, "sessionMatches" to (token == controller.sessionToken),
                "title" to value.extras.getCharSequence(Notification.EXTRA_TITLE),
                "text" to value.extras.getCharSequence(Notification.EXTRA_TEXT),
                "subText" to value.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                "largeIcon" to value.getLargeIcon(), "smallIcon" to value.smallIcon)
        } }
    }

    private data class NotificationLyricSettingsSnapshot(
        val dynamicIsland: Boolean, val externalLyrics: Boolean, val externalTranslation: Boolean, val colorOs: Boolean
    )

    private data class PlayingSessionText(
        val title: String, val artist: String, val displayTitle: String, val displaySubtitle: String,
        val lyric: String?, val translation: String?, val forceLyrics: Boolean, val colorOs: Boolean,
        val audioDeviceType: Int?, val bluetoothMode: String
    )

    private fun currentPlayingSessionText(song: SongItem): PlayingSessionText? {
        val service = activeService() ?: return null
        val owner = checkNotNull((service.field("presentationOwnerDelegate") as Lazy<*>).value)
        val source = owner.field("source")
        val inputs = source.javaClass.getMethod("metadata").invoke(source)
        fun input(name: String): Any? = inputs.javaClass.getMethod(name).invoke(inputs)
        val payload = checkNotNull(input("getPayload"))
        val forceLyrics = input("getForceSendLyrics") as Boolean
        val colorOs = input("getColorOsLyricsEnabled") as Boolean
        val audioDeviceType = input("getAudioDeviceType") as? Int
        val bluetoothMode = owner.field("bluetoothMode")
        val method = Class.forName(
            "moe.ouom.neriplayer.core.player.service.presentation.PlaybackServiceMetadataPresentationKt"
        ).declaredMethods.single { it.name == "serviceMetadataText" }.apply { isAccessible = true }
        val projection = checkNotNull(method.invoke(null, song, payload, audioDeviceType, forceLyrics,
            bluetoothMode, input("getAlbum"), colorOs))
        fun text(name: String): String = projection.javaClass.getMethod(name).invoke(projection) as String
        fun lyric(name: String): String? = payload.javaClass.getMethod(name).invoke(payload) as? String
        return PlayingSessionText(text("getTitle"), text("getArtist"), text("getDisplayTitle"),
            text("getDisplaySubtitle"), lyric("getLyric"), lyric("getTranslation"), forceLyrics, colorOs,
            audioDeviceType, bluetoothMode.toString())
    }

    private fun assumeNoFixtureSyncTargets() {
        val noTargets = runCatching {
            val keysets = setOf("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__")
            val webDavEmpty = context.getSharedPreferences("webdav_secure_prefs", Context.MODE_PRIVATE)
                .all.keys.all { it in keysets }
            val github = SecureTokenStorage(context)
            webDavEmpty && github.isPersistent && github.getToken().isNullOrEmpty() &&
                github.getRepoOwner().isNullOrEmpty() && github.getRepoName().isNullOrEmpty()
        }.getOrDefault(false)
        assumeTrue("仅允许没有 GitHub 或 WebDAV 同步目标的专用模拟器", noTargets)
    }

    private suspend fun removePlayingFixtureStatistics(directory: File, songs: List<SongItem>) {
        if (songs.isEmpty()) return
        flushPlaybackStatsPendingWrites(context)
        val history = AppContainer.playHistoryRepo
        check(history.awaitHistoryLoaded())
        history.removeSongs(songs)
        withTimeout(TimeUnit.SECONDS.toMillis(ServiceTimeoutSeconds)) {
            history.historyFlow.first { entries -> entries.none { entry ->
                songs.any { song -> entry.id == song.id && entry.localFilePath == song.localFilePath }
            } }
        }
        val statistics = AppContainer.playbackStatsRepo
        val keys = mutableSetOf<String>()
        var cursor: PlaybackStatsCursor? = null
        do {
            val page = statistics.readPage(PlaybackStatsQuery(), after = cursor)
            page.tracks.filter { it.localFilePath?.startsWith("${directory.path}/") == true }
                .mapTo(keys) { it.identityKey }
            cursor = page.nextCursor
        } while (cursor != null)
        statistics.removeTracks(keys)
        withTimeout(TimeUnit.SECONDS.toMillis(ServiceTimeoutSeconds)) {
            while (keys.any { statistics.getStatForTrack(it) != null }) delay(50L)
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
        const val PlaybackNotificationChannel = "neriplayer_playback_channel"
        const val OriginalTitle = "Original title"
        const val OriginalArtist = "Original artist"
        const val OriginalLyric = "[00:01.00]original lyric"
        const val OriginalTranslation = "[00:01.00]original translation"
        const val OriginalRomanization = "[00:01.00]original romanization"
        val QueueFiles = listOf("last_playlist.json", "last_playback_state.json")
    }
}
