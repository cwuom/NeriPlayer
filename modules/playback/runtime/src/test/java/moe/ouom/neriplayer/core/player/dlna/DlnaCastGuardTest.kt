package moe.ouom.neriplayer.core.player.dlna

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.dlna.session.DlnaServiceKeepAliveController
import moe.ouom.neriplayer.core.player.dlna.session.PlayerManagerDlnaAdapter
import moe.ouom.neriplayer.core.player.lan.LanPlaybackBinding
import moe.ouom.neriplayer.core.player.service.usb.UsbExclusiveKeepAliveServiceHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DlnaCastGuardTest {

    @Test
    fun castingIsBlockedWhileListenTogetherOwnsPlayback() {
        val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
        val previousApplication = applicationField.get(null)
        val previousExternalAudio = LanPlaybackBinding.externalAudio
        try {
            applicationField.set(null, RuntimeEnvironment.getApplication())

            LanPlaybackBinding.externalAudio = false
            assertNull(PlayerManagerDlnaAdapter.castBlockedReason())

            // 一起听由独立的 LAN 引擎发声并消费播放命令，此时投送会和房间音频同时出声
            LanPlaybackBinding.externalAudio = true
            assertNotNull(PlayerManagerDlnaAdapter.castBlockedReason())
        } finally {
            LanPlaybackBinding.externalAudio = previousExternalAudio
            applicationField.set(null, previousApplication)
        }
    }

    @Test
    fun keepAlive_reassertsForegroundImmediatelyOnSamsungLockScreenBackgroundEntry() {
        val scope = TestScope(StandardTestDispatcher())
        var fgStarted = true
        val reassertReasons = mutableListOf<String>()
        var ensureCalls = 0
        val forceRenewHistory = mutableListOf<Boolean>()
        var appInForeground = true
        var holdKeepAlive = true

        val host = object : UsbExclusiveKeepAliveServiceHost {
            override fun foregroundStarted(): Boolean = fgStarted
            override fun reassertForeground(reason: String): Boolean {
                reassertReasons += reason
                return true
            }
            override fun ensureForeground(): Boolean {
                ensureCalls++
                fgStarted = true
                return true
            }
            override fun onForegroundFailure(reason: String) = Unit
            override fun updatePlaybackPresentation() = Unit
        }

        val controller = DlnaServiceKeepAliveController(
            context = RuntimeEnvironment.getApplication(),
            scope = scope,
            host = host,
            holdKeepAliveProvider = { holdKeepAlive },
            appInForegroundProvider = { appInForeground },
            renewLocksAction = { force -> forceRenewHistory += force }
        )

        try {
            controller.update("foreground_start")
            assertTrue(reassertReasons.isEmpty())

            // 三星锁屏界面 / AOD 下屏幕可能仍处于 interactive=true，但 Activity 已退到后台 (ON_PAUSE)：
            // 必须立即强制续租 WakeLock/WifiLock 并重申 mediaPlayback 前台服务，不能等息屏广播或长周期轮询
            appInForeground = false
            controller.update("lifecycle_on_pause")
            assertEquals(listOf(true), forceRenewHistory)
            assertEquals(listOf("dlna_bg_enter:lifecycle_on_pause"), reassertReasons)

            scope.advanceTimeBy(2_000L)
            scope.runCurrent()
            assertTrue(reassertReasons.contains("dlna_keepalive"))

            // 若进入后台时前台服务尚未启动，也必须在当拍立即拉起前台服务
            appInForeground = true
            controller.update("lifecycle_resume")
            fgStarted = false
            appInForeground = false
            controller.update("lifecycle_on_stop")
            assertEquals(1, ensureCalls)
            assertTrue(fgStarted)
        } finally {
            holdKeepAlive = false
            controller.close()
        }
    }
}
