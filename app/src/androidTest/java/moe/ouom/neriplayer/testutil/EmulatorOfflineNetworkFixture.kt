package moe.ouom.neriplayer.testutil

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.traffic.LikelyNetworkTransportAvailability
import moe.ouom.neriplayer.data.traffic.currentLikelyNetworkTransportAvailability

internal class EmulatorOfflineNetworkFixture private constructor(
    private val context: Context,
    private val originalAirplaneMode: Boolean,
    private val originalWifiEnabled: Boolean,
    private val originalAvailability: LikelyNetworkTransportAvailability
) : AutoCloseable {
    private var airplaneModeChanged = false
    private var wifiConfigured = false
    private var closed = false

    private fun enterOffline() {
        if (!originalAirplaneMode) {
            airplaneModeChanged = true
            shell("cmd connectivity airplane-mode enable")
            awaitState("飞行模式未开启") { airplaneMode(context) }
        }
        // 安装和 runner 启动后再次确认实际离线，不依赖先前命令返回
        wifiConfigured = true
        shell("cmd wifi set-wifi-enabled disabled")
        awaitState("模拟器网络未进入离线状态") {
            !wifiEnabled() &&
                context.currentLikelyNetworkTransportAvailability() == LikelyNetworkTransportAvailability.OFFLINE
        }
        Log.i("EmulatorOfflineNetwork", "offline confirmed: originalAirplaneMode=$originalAirplaneMode, " +
            "originalWifiEnabled=$originalWifiEnabled, originalAvailability=$originalAvailability")
    }

    override fun close() {
        if (closed) return
        closed = true
        val failures = mutableListOf<Throwable>()
        fun restore(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let(failures::add)
        }
        if (airplaneModeChanged) restore {
            shell("cmd connectivity airplane-mode disable")
            awaitState("未恢复原始飞行模式") { !airplaneMode(context) }
        }
        if (wifiConfigured || airplaneModeChanged) restore {
            val mode = if (originalWifiEnabled) "enabled" else "disabled"
            shell("cmd wifi set-wifi-enabled $mode")
            awaitState("未恢复原始 Wi-Fi 开关") { wifiEnabled() == originalWifiEnabled }
        }
        if (originalAvailability == LikelyNetworkTransportAvailability.ONLINE) restore {
            // 开关恢复不代表默认网络已重新关联，不能让后续用例接收这段过渡
            awaitState("未恢复原始在线状态") {
                context.currentLikelyNetworkTransportAvailability() == LikelyNetworkTransportAvailability.ONLINE
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    companion object {
        fun start(context: Context): EmulatorOfflineNetworkFixture {
            check(Build.VERSION.SDK_INT >= 30 && Build.HARDWARE in setOf("ranchu", "goldfish")) {
                "离线网络夹具只能操作 API 30+ 模拟器"
            }
            check(Looper.myLooper() != Looper.getMainLooper()) { "网络夹具不能阻塞主线程" }
            val fixture = EmulatorOfflineNetworkFixture(context, airplaneMode(context), wifiEnabled(),
                context.currentLikelyNetworkTransportAvailability())
            try {
                fixture.enterOffline()
                return fixture
            } catch (failure: Throwable) {
                runCatching { fixture.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        private fun airplaneMode(context: Context): Boolean {
            val value = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON)
            check(value == 0 || value == 1) { "无法识别飞行模式，禁止改变网络" }
            return value == 1
        }

        private fun wifiEnabled(): Boolean = when (shell("cmd wifi status").lineSequence().first()) {
            "Wifi is enabled" -> true
            "Wifi is disabled" -> false
            else -> error("无法识别 Wi-Fi 状态，禁止改变网络")
        }

        private fun awaitState(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000L
            while (!condition()) {
                check(SystemClock.uptimeMillis() < deadline) { message }
                SystemClock.sleep(50L)
            }
        }

        private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            }
    }
}
