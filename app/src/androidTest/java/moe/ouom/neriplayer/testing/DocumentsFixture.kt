package moe.ouom.neriplayer.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object DocumentsFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun setupDelayedProvider(options: Bundle): Bundle = launch(options = options)

    fun createExternalTree(): Uri {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "external fixtures require an emulator" }
        val name = "NeriPlayer-test-${UUID.randomUUID()}"
        val path = "/sdcard/Download/$name"
        val automation = instrumentation.uiAutomation
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        shell("mkdir $path")
        val createdPath = shell("ls -d $path").trim()
        check(createdPath == path) { "cannot create test directory: $createdPath" }
        try {
            val initial = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/$name"
            )
            val result = launch(initial = initial, confirmPicker = true)
            return requireNotNull(result.getString("treeUri")).toUri().also { tree ->
                instrumentation.targetContext.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (error: Throwable) {
            // 授权失败时只移除本次创建的空目录，保留任何意外写入的文件
            shell("rmdir $path")
            throw error
        }
    }

    private fun launch(options: Bundle? = null, initial: Uri? = null, confirmPicker: Boolean = false): Bundle {
        val completed = CountDownLatch(1)
        val response = AtomicReference<Bundle>()
        val resultCode = AtomicReference<Int>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, result: Bundle) {
                resultCode.set(code)
                response.set(result)
                completed.countDown()
            }
        }
        val intent = Intent().setClassName(instrumentation.context.packageName, DocumentsFixtureActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DocumentsFixtureActivity.RECEIVER, receiver)
            .putExtra(DocumentsFixtureActivity.TARGET_PACKAGE, instrumentation.targetContext.packageName)
            .putExtra(DocumentsFixtureActivity.SETUP, options)
            .setData(initial)
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        if (confirmPicker) {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        }
        try {
            instrumentation.targetContext.startActivity(intent)
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var selected = false
            while (completed.count != 0L && SystemClock.elapsedRealtime() < deadline) {
                if (confirmPicker) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (root?.packageName?.toString() in setOf("com.android.documentsui", "com.google.android.documentsui")) {
                        val ids = if (selected) listOf("android:id/button1") else listOf(
                            "com.android.documentsui:id/action_menu_select",
                            "com.google.android.documentsui:id/action_menu_select",
                            "android:id/button1"
                        )
                        for (id in ids) {
                            val node = root?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isEnabled && it.isClickable }
                            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                                selected = true
                                break
                            }
                        }
                    }
                }
                completed.await(50, TimeUnit.MILLISECONDS)
            }
            check(completed.count == 0L) { "test directory authorization timed out: $initial" }
            check(resultCode.get() == Activity.RESULT_OK) { "test directory authorization failed: ${response.get()}" }
            return requireNotNull(response.get())
        } finally {
            if (confirmPicker) {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            }
        }
    }
}
