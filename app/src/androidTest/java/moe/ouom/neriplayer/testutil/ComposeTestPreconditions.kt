package moe.ouom.neriplayer.testutil

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse

fun assumeComposeHostAvailable() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    assumeTrue("设备当前未点亮屏幕，Compose instrumentation 宿主无法稳定拉起", powerManager.isInteractive)

    val windowPolicyDump = instrumentation.uiAutomation
        .executeShellCommand("dumpsys window policy")
        .use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader()
                .use { it.readText() }
        }
    val keyguardShowingByWindowPolicy = Regex(
        pattern = """(?m)^\s*(showing|mIsShowing)=true\s*$"""
    ).containsMatchIn(windowPolicyDump)

    assumeFalse(
        "设备当前处于锁屏状态，Compose instrumentation 宿主无法稳定拉起",
        keyguardManager.isDeviceLocked ||
            keyguardManager.isKeyguardLocked ||
            keyguardShowingByWindowPolicy
    )
}
