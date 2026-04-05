package moe.ouom.neriplayer.testutil

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.content.ContextCompat
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

fun playbackRuntimePermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
        permissions += Manifest.permission.READ_MEDIA_AUDIO
        permissions += Manifest.permission.READ_MEDIA_IMAGES
        permissions += Manifest.permission.READ_MEDIA_VIDEO
    } else {
        permissions += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_CONNECT
    }
    return permissions.toTypedArray()
}

private fun readShellCommand(command: String): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return instrumentation.uiAutomation
        .executeShellCommand(command)
        .use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader()
                .use { it.readText() }
        }
}

private fun permissionAppOp(permission: String): String? {
    return when (permission) {
        Manifest.permission.POST_NOTIFICATIONS -> "POST_NOTIFICATION"
        Manifest.permission.READ_MEDIA_AUDIO -> "READ_MEDIA_AUDIO"
        Manifest.permission.READ_MEDIA_IMAGES -> "READ_MEDIA_IMAGES"
        Manifest.permission.READ_MEDIA_VIDEO -> "READ_MEDIA_VIDEO"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "READ_EXTERNAL_STORAGE"
        Manifest.permission.BLUETOOTH_CONNECT -> "BLUETOOTH_CONNECT"
        else -> null
    }
}

private fun isPermissionSatisfied(context: Context, permission: String): Boolean {
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    val appOp = permissionAppOp(permission) ?: return false
    val appOpState = readShellCommand("cmd appops get ${context.packageName} $appOp")
    return Regex("""\ballow\b""").containsMatchIn(appOpState)
}

fun grantRuntimePermissions(vararg permissions: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val packageName = context.packageName

    permissions
        .distinct()
        .forEach { permission ->
            if (isPermissionSatisfied(context, permission)) {
                return@forEach
            }
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
            }.recoverCatching {
                val appOp = permissionAppOp(permission)
                    ?: throw it
                readShellCommand("cmd appops set $packageName $appOp allow")
            }.getOrThrow()
            check(isPermissionSatisfied(context, permission)) {
                "权限授予失败: $permission"
            }
        }
}
