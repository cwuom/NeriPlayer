package moe.ouom.neriplayer.testutil

import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.traffic.LikelyNetworkTransportAvailability
import moe.ouom.neriplayer.data.traffic.currentLikelyNetworkTransportAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorOfflineNetworkFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun requireEmulator() {
        assumeTrue("网络夹具只能操作 API 30+ 模拟器", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test
    fun disablesEnabledWifiAfterRunnerStartupAndRestoresItOnClose() = withWifiEnabled {
        val airplaneMode = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON)
        val availability = context.currentLikelyNetworkTransportAvailability()
        EmulatorOfflineNetworkFixture.start(context).use {
            assertOffline()
        }
        assertEquals("关闭夹具后应恢复 Wi-Fi", "Wifi is enabled", wifiStatus())
        assertEquals(airplaneMode,
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertRestoredAvailability(availability)
    }

    @Test
    fun restoresNetworkWithoutReplacingTheTestFailure() = withWifiEnabled {
        val airplaneMode = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON)
        val availability = context.currentLikelyNetworkTransportAvailability()
        val expectedFailure = IllegalStateException("intentional fixture test failure")
        val actualFailure = assertThrows(IllegalStateException::class.java) {
            EmulatorOfflineNetworkFixture.start(context).use {
                assertOffline()
                throw expectedFailure
            }
        }
        assertSame(expectedFailure, actualFailure)
        assertEquals("异常退出后应恢复 Wi-Fi", "Wifi is enabled", wifiStatus())
        assertEquals(airplaneMode,
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertRestoredAvailability(availability)
    }

    private fun assertOffline() {
        assertEquals("Wifi is disabled", wifiStatus())
        assertEquals("夹具返回前 Wi-Fi 必须已完成关闭", WifiManager.WIFI_STATE_DISABLED,
            context.registerReceiver(null, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
                ?.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertEquals(LikelyNetworkTransportAvailability.OFFLINE,
            context.currentLikelyNetworkTransportAvailability())
    }

    private fun withWifiEnabled(action: () -> Unit) {
        val originalWifi = wifiStatus()
        check(originalWifi in setOf("Wifi is enabled", "Wifi is disabled"))
        val originalAvailability = context.currentLikelyNetworkTransportAvailability()
        var failure: Throwable? = null
        try {
            shell("cmd wifi set-wifi-enabled enabled")
            awaitWifi("Wifi is enabled")
            if (originalAvailability == LikelyNetworkTransportAvailability.ONLINE) awaitOnline()
            action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                val mode = if (originalWifi == "Wifi is enabled") "enabled" else "disabled"
                shell("cmd wifi set-wifi-enabled $mode")
                awaitWifi(originalWifi)
                if (originalAvailability == LikelyNetworkTransportAvailability.ONLINE) awaitOnline()
                assertRestoredAvailability(originalAvailability)
            } catch (cleanupFailure: Throwable) {
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun awaitWifi(expected: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (wifiStatus() != expected) {
            check(SystemClock.uptimeMillis() < deadline) { "Wi-Fi 未进入预期状态: $expected" }
            SystemClock.sleep(50L)
        }
    }

    private fun awaitOnline() {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (context.currentLikelyNetworkTransportAvailability() != LikelyNetworkTransportAvailability.ONLINE) {
            check(SystemClock.uptimeMillis() < deadline) { "未恢复原始在线状态" }
            SystemClock.sleep(50L)
        }
    }

    private fun assertRestoredAvailability(original: LikelyNetworkTransportAvailability) {
        if (original == LikelyNetworkTransportAvailability.ONLINE) {
            assertEquals("退出夹具时必须已恢复原始在线状态", LikelyNetworkTransportAvailability.ONLINE,
                context.currentLikelyNetworkTransportAvailability())
        }
    }

    private fun wifiStatus(): String = shell("cmd wifi status").lineSequence().first()

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
}
