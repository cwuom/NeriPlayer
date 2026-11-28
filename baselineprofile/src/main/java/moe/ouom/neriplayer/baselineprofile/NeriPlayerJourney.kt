package moe.ouom.neriplayer.baselineprofile

import android.content.Context
import android.content.res.Configuration
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import java.util.Locale
import java.util.regex.Pattern

internal val targetPackage: String =
    requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId")) {
        "targetAppId instrumentation argument is missing"
    }

private const val FIXTURE_RECEIVER = "moe.ouom.neriplayer.benchmark.BaselineProfileFixtureReceiver"
private const val FIXTURE_ACTION = "moe.ouom.neriplayer.benchmark.PREPARE_STARTUP"
private const val UI_TIMEOUT_MS = 15_000L
private const val FIXTURE_ATTEMPTS = 3
private const val FIXTURE_RETRY_DELAY_MS = 500L

// 导航文案从被测包读取，应用只提供中英文资源，两种语言都要能匹配
private val navigationLabelNames = listOf("nav_home", "nav_explore", "nav_library", "nav_settings")
private val navigationLabels: List<Pattern> by lazy {
    val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
    val packageContext = instrumentationContext.createPackageContext(targetPackage, 0)
    val localizedContexts = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).map { locale ->
        packageContext.createConfigurationContext(
            Configuration(packageContext.resources.configuration).apply { setLocale(locale) }
        )
    }
    navigationLabelNames.map { name ->
        val labels = localizedContexts.map { it.string(name) }.distinct()
        Pattern.compile(labels.joinToString("|") { Pattern.quote(it) })
    }
}

private fun Context.string(name: String): String {
    val id = resources.getIdentifier(name, "string", targetPackage)
    check(id != 0) { "string/$name not found in $targetPackage" }
    return getString(id)
}

/** 通过 benchmark 变体里的 fixture 设置首启引导状态，返回前写入已落盘。 */
internal fun MacrobenchmarkScope.setOnboardingCompleted(completed: Boolean) {
    val command =
        "am broadcast -a $FIXTURE_ACTION -n $targetPackage/$FIXTURE_RECEIVER --ez onboardingCompleted $completed"
    // 刚 force-stop 的进程可能还在退出，广播会落到旧进程上丢失，短暂等待后重发
    val outputs = mutableListOf<String>()
    repeat(FIXTURE_ATTEMPTS) {
        val output = device.executeShellCommand(command)
        if ("result=-1" in output) return
        outputs += output.trim()
        Thread.sleep(FIXTURE_RETRY_DELAY_MS)
    }
    error("startup fixture failed: ${outputs.joinToString(" | ")}")
}

internal fun MacrobenchmarkScope.waitForMainScreen() {
    val explore = navigationLabels[1]
    check(device.wait(Until.hasObject(By.desc(explore)), UI_TIMEOUT_MS)) {
        "main screen navigation did not appear"
    }
}

internal fun MacrobenchmarkScope.waitForOnboarding() {
    check(device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), UI_TIMEOUT_MS)) {
        "onboarding did not appear"
    }
    device.waitForIdle()
}

/** 依次进入每个底部标签并上下滑动，覆盖首屏后最常走的列表与切页路径。 */
internal fun MacrobenchmarkScope.browseMainTabs() {
    navigationLabels.forEach { label ->
        // 首页标签可被用户关闭，找不到就跳过
        val tab = device.findObject(By.desc(label)) ?: return@forEach
        tab.click()
        device.waitForIdle()
        fling(Direction.DOWN)
        fling(Direction.UP)
    }
}

private fun MacrobenchmarkScope.fling(direction: Direction) {
    // 列表重组后旧节点会失效，每次都重新查找；仍然失效就跳过这一次
    val list = device.findObject(By.scrollable(true)) ?: return
    try {
        // 避开系统手势区，防止 fling 被当成返回或回到桌面
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(direction)
    } catch (_: StaleObjectException) {
        return
    }
    device.waitForIdle()
}
