package moe.ouom.neriplayer.testutil

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource

internal class SoftwareKeyboardRule : ExternalResource() {
    var originalPreference: String = "not captured"
        private set
    private var captured = false

    override fun before() {
        val previous = shell("settings get secure show_ime_with_hard_keyboard")
        check(previous in setOf("null", "0", "1")) { "未知的软键盘设置，不能安全恢复: $previous" }
        originalPreference = previous
        captured = true
        try {
            // 硬件键盘策略属于模拟器环境，不能决定真实 IME 回归是否执行
            shell("settings put secure show_ime_with_hard_keyboard 1")
            assertEquals("软键盘测试前置必须生效，原值=$previous", "1",
                shell("settings get secure show_ime_with_hard_keyboard"))
        } catch (failure: Throwable) {
            runCatching { restorePreference() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun after() = restorePreference()

    private fun restorePreference() {
        if (!captured) return
        if (originalPreference == "null") shell("settings delete secure show_ime_with_hard_keyboard")
        else shell("settings put secure show_ime_with_hard_keyboard $originalPreference")
        assertEquals("软键盘设置必须恢复原值", originalPreference,
            shell("settings get secure show_ime_with_hard_keyboard"))
        captured = false
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }
}
