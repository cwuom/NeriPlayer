package moe.ouom.neriplayer.testing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.car.CarMediaBrowserService
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.startup.app.InstrumentationTestRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CarMediaBrowserIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var browser: MediaBrowser? = null
    private var playbackConnection: ServiceConnection? = null
    private var playbackBinder: IBinder? = null
    private var serviceWasActive = false

    @Before
    fun requireIdleApplicationRuntime() {
        assertTrue("必须使用应用既有 instrumentation runner", InstrumentationTestRuntime.isActive)
        assertTrue("Application 必须先安装播放器依赖", PlayerDependencies.isInitialized())
        assumeFalse("安全模式不创建车载媒体服务", PlayerDependencies.presentation.shouldEnterSafeMode(context))
        serviceWasActive = AudioPlayerService.isInstanceActiveForDiagnostics()
        assumeFalse("已有播放时不干预播放器", hasActivePlayback())
        assumeFalse("已有前台播放服务时不干预播放器", AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    @After
    fun disconnectTestClients() {
        val hadBrowser = browser != null
        instrumentation.runOnMainSync {
            playbackConnection?.let { context.unbindService(it) }
            playbackConnection = null
            playbackBinder = null
            browser?.disconnect()
            browser = null
        }
        if (hadBrowser && !serviceWasActive && !hasActivePlayback()) awaitServiceDestroyed()
    }

    @Test
    fun frameworkBrowserReusesPlaybackSessionWithoutPlayingOrStartingForeground() {
        val connected = connectBrowser()
        val children = children(connected)
        assertEquals(CarMediaIds.ROOT, connected.root)
        assertTrue("根目录必须提供分类", children.isNotEmpty())
        assertTrue("根目录最多四个分类", children.size <= 4)
        assertTrue("根目录只提供可浏览分类", children.all { it.isBrowsable && !it.isPlayable })

        assertEquals(playbackSessionToken(), connected.sessionToken)
        val controller = MediaController(context, connected.sessionToken)
        assertEquals(context.packageName, controller.packageName)
        assertFalse("浏览不能启用前台播放服务", AudioPlayerService.isForegroundActiveForDiagnostics())
        assertFalse("浏览不能开始播放", hasActivePlayback())
        assertFalse(
            "共享会话不能发布播放状态",
            controller.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING),
        )

        val invalidItem = CompletableFuture<MediaBrowser.MediaItem?>()
        instrumentation.runOnMainSync {
            connected.getItem("content://untrusted/audio", object : MediaBrowser.ItemCallback() {
                override fun onItemLoaded(item: MediaBrowser.MediaItem?) {
                    invalidItem.complete(item)
                }

                override fun onError(itemId: String) {
                    invalidItem.completeExceptionally(AssertionError("非法 ID 应返回空曲目: $itemId"))
                }
            })
        }
        assertNull(invalidItem.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun rootLimitOneGroupsCatalogueWithoutHidingItsFourCategories() {
        val connected = connectBrowser(Bundle().apply {
            putInt(ROOT_CHILDREN_LIMIT_HINT, 1)
        })
        assertEquals(CarMediaIds.ROOT, connected.root)
        val groupedRoot = children(connected)
        assertEquals(listOf(CarMediaIds.CATALOGUE), groupedRoot.map { it.mediaId })
        assertTrue("分组根目录必须可浏览且不可直接播放", groupedRoot.all { it.isBrowsable && !it.isPlayable })

        val categories = children(connected, CarMediaIds.CATALOGUE)
        assertEquals(
            listOf(CarMediaIds.QUEUE, CarMediaIds.PLAYLISTS, CarMediaIds.HISTORY, CarMediaIds.OFFLINE),
            categories.map { it.mediaId },
        )
        assertTrue("下钻必须保留四个可浏览分类", categories.all { it.isBrowsable && !it.isPlayable })
        assertFalse("分组浏览不能开始播放", hasActivePlayback())
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    @Test
    fun offlineBrowseOnlyHintKeepsAnAccessibleCategoryRoot() {
        val connected = connectBrowser(Bundle().apply {
            putBoolean("android.service.media.extra.OFFLINE", true)
            putInt(ROOT_CHILDREN_LIMIT_HINT, 1)
            putInt(ROOT_CHILDREN_FLAGS_HINT, MediaBrowser.MediaItem.FLAG_BROWSABLE)
        })
        assertEquals(CarMediaIds.ROOT, connected.root)
        val root = children(connected)
        assertEquals(listOf(CarMediaIds.OFFLINE), root.map { it.mediaId })
        assertTrue(root.single().isBrowsable)
        assertFalse(root.single().isPlayable)
        assertFalse(hasActivePlayback())
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    @Test
    fun injectedForegroundFailureKeepsBoundSessionUsableAndCancelsOldCommands() {
        val connected = connectBrowser()
        children(connected)
        val token = playbackSessionToken()
        val service = AudioPlayerService::class.java.getDeclaredField("activeServiceInstance").apply {
            isAccessible = true
        }.get(null) as AudioPlayerService
        val started = AudioPlayerService::class.java.getDeclaredField("hasReceivedStartCommand").apply {
            isAccessible = true
        }
        val pending = AudioPlayerService::class.java.getDeclaredField("pendingPlayerActions").apply {
            isAccessible = true
        }.get(service)
        val scope = AudioPlayerService::class.java.getDeclaredField("serviceScope").apply {
            isAccessible = true
        }.get(service) as CoroutineScope
        val failure = AudioPlayerService::class.java.getDeclaredMethod(
            "handleForegroundPromotionFailure", String::class.java, Int::class.javaObjectType,
        ).apply { isAccessible = true }
        var pendingActionExecuted = false
        val pendingAction = { pendingActionExecuted = true }

        instrumentation.runOnMainSync {
            started.setBoolean(service, true)
            pending.javaClass.getMethod("addLast", Any::class.java).invoke(pending, pendingAction)
            // 注入失败清理路径，不改变系统权限或触发真实的前台服务拒绝
            failure.invoke(service, "instrumented_foreground_failure", null)
        }
        assertTrue(AudioPlayerService.isInstanceActiveForDiagnostics())
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
        assertFalse(started.getBoolean(service))
        assertTrue((pending as Collection<*>).isEmpty())
        assertFalse(pendingActionExecuted)
        assertTrue(checkNotNull(scope.coroutineContext[Job]).children.any { it.isActive })
        assertEquals(token, playbackSessionToken())
        assertTrue(children(connected).isNotEmpty())

        val controller = MediaController(context, token)
        val queue = PlayerManager.currentQueueFlow.value.toList()
        val song = PlayerManager.currentSongFlow.value
        val position = PlayerManager.playbackPositionFlow.value
        runBlocking {
            // 已暂停的相同状态会被去重，观察实际播放器命令而不是等待重复状态回调
            val paused = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)) {
                    PlayerManager.playbackCommandFlow.first { it.type == "PAUSE" }
                }
            }
            instrumentation.runOnMainSync {
                controller.transportControls.pause()
            }
            paused.await()
        }
        assertEquals("故障后暂停命令必须保留队列", queue, PlayerManager.currentQueueFlow.value)
        assertEquals(song, PlayerManager.currentSongFlow.value)
        assertEquals(position, PlayerManager.playbackPositionFlow.value)
        assertFalse(hasActivePlayback())
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    @Test
    fun disconnectKeepsRestoredPausedPlaybackState() {
        assumeFalse("已有服务绑定无法验证本次浏览解绑生命周期", serviceWasActive)
        val connected = connectBrowser()
        children(connected)
        val queue = PlayerManager.currentQueueFlow.value.toList()
        val song = PlayerManager.currentSongFlow.value
        val position = PlayerManager.playbackPositionFlow.value
        assertFalse(hasActivePlayback())

        instrumentation.runOnMainSync {
            connected.disconnect()
            browser = null
        }
        awaitServiceDestroyed()
        assertEquals("解绑必须保留暂停队列", queue, PlayerManager.currentQueueFlow.value)
        assertEquals("解绑必须保留选中曲目", song, PlayerManager.currentSongFlow.value)
        assertEquals("解绑必须保留恢复位置", position, PlayerManager.playbackPositionFlow.value)
        assertFalse("解绑不能开始播放", hasActivePlayback())
        assertFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
    }

    private fun connectBrowser(rootHints: Bundle? = null): MediaBrowser {
        val connected = CompletableFuture<MediaBrowser>()
        instrumentation.runOnMainSync {
            val client = MediaBrowser(
                context,
                ComponentName(context, CarMediaBrowserService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        connected.complete(checkNotNull(browser))
                    }

                    override fun onConnectionFailed() {
                        connected.completeExceptionally(AssertionError("Framework browser 连接失败"))
                    }

                    override fun onConnectionSuspended() {
                        connected.completeExceptionally(AssertionError("Framework browser 连接中断"))
                    }
                },
                rootHints,
            )
            browser = client
            client.connect()
        }
        return connected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun children(client: MediaBrowser, parentId: String = client.root): List<MediaBrowser.MediaItem> {
        val loaded = CompletableFuture<List<MediaBrowser.MediaItem>>()
        instrumentation.runOnMainSync {
            client.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                    loaded.complete(children.toList())
                }

                override fun onError(parentId: String) {
                    loaded.completeExceptionally(AssertionError("浏览目录加载失败: $parentId"))
                }
            })
        }
        return loaded.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun playbackSessionToken(): MediaSession.Token {
        playbackBinder?.let { return sessionToken(it) }
        val token = CompletableFuture<MediaSession.Token>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    // binder 是 runtime 模块 internal 类型, 不为测试扩大生产可见性
                    playbackBinder = checkNotNull(binder)
                    token.complete(sessionToken(binder))
                } catch (error: Exception) {
                    token.completeExceptionally(error)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackBinder = null
                token.completeExceptionally(AssertionError("播放服务绑定中断"))
            }
        }
        instrumentation.runOnMainSync {
            val intent = Intent(context, AudioPlayerService::class.java).setAction(ACTION_BIND_CAR)
            assertTrue("必须绑定到已有播放服务", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            playbackConnection = connection
        }
        return token.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun sessionToken(binder: IBinder): MediaSession.Token {
        val value = binder.javaClass.getMethod("getSessionToken").invoke(binder)
        return value as? MediaSession.Token ?: error("播放服务没有 framework session token")
    }

    private fun awaitServiceDestroyed() {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
        while (AudioPlayerService.isInstanceActiveForDiagnostics() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25)
        }
        assertFalse("浏览解绑后必须销毁未启动的播放服务", AudioPlayerService.isInstanceActiveForDiagnostics())
    }

    private fun hasActivePlayback(): Boolean =
        PlayerManager.isPlayingFlow.value || PlayerManager.playWhenReadyFlow.value ||
            PlayerManager.playbackControlPlayingFlow.value

    private companion object {
        const val TIMEOUT_SECONDS = 25L
        const val ACTION_BIND_CAR = "moe.ouom.neriplayer.action.BIND_CAR"
        const val ROOT_CHILDREN_LIMIT_HINT = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
        const val ROOT_CHILDREN_FLAGS_HINT = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"
    }
}
