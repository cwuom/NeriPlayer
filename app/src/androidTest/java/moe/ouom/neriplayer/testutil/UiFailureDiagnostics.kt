package moe.ouom.neriplayer.testutil

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.ArrayDeque

internal object UiFailureDiagnostics {
    private const val TAG = "UiFailureDiagnostics"
    private const val MAX_WINDOWS = 12
    private const val MAX_NODES = 64
    private const val MAX_DEPTH = 8
    private const val MAX_CHILDREN = 12
    private const val MAX_TEXT = 160
    private const val MAX_OUTPUT = 32_768
    private const val LOG_CHUNK = 1_000

    fun <T> onFailure(label: String, action: () -> T): T = try {
        action()
    } catch (failure: Throwable) {
        record(label, failure)
        throw failure
    }

    fun record(label: String, failure: Throwable) {
        // 诊断只补充现场证据，任何采集失败都不能代替原测试异常
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            log(label, "original", "${failure.javaClass.name}: ${failure.message}")
            runCatching { log(label, "windows", windows(automation)) }
                .onFailure { logCaptureError(label, "windows", it) }
            for (service in listOf("window windows", "activity top")) {
                runCatching { log(label, service, shell(automation, "dumpsys -t 3 $service")) }
                    .onFailure { logCaptureError(label, service, it) }
            }
            runCatching {
                val bitmap = checkNotNull(automation.takeScreenshot()) { "screenshot unavailable" }
                try {
                    val directory = File(instrumentation.targetContext.cacheDir, "ui-failure-diagnostics")
                    check(directory.isDirectory || directory.mkdirs()) { "cannot create screenshot directory" }
                    val name = label.replace(Regex("[^a-zA-Z0-9_-]"), "-").take(60)
                    val file = File(directory, "$name-${SystemClock.uptimeMillis()}.png")
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    log(label, "screenshot", file.absolutePath)
                } finally {
                    bitmap.recycle()
                }
            }.onFailure { logCaptureError(label, "screenshot", it) }
        }.onFailure { logCaptureError(label, "capture", it) }
    }

    private fun windows(automation: UiAutomation): String = buildString {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val active = automation.rootInActiveWindow
        appendLine("activeRoot: ${active?.packageName}, class=${active?.className}, window=${active?.windowId}")
        val windows = automation.windows
        appendLine("windowCount=${windows.size}")
        for (window in windows.take(MAX_WINDOWS)) {
            val root = window.root
            appendLine("window=${window.id}, title=${text(window.title)}, type=${window.type}, layer=${window.layer}, " +
                "active=${window.isActive}, focused=${window.isFocused}, accessibilityFocused=${window.isAccessibilityFocused}, " +
                "package=${root?.packageName}, rootClass=${root?.className}")
            if (root != null) appendNodes(root, this)
            if (length >= MAX_OUTPUT) break
        }
    }.take(MAX_OUTPUT)

    private fun appendNodes(root: AccessibilityNodeInfo, output: StringBuilder) {
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(root to 0)
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_NODES && output.length < MAX_OUTPUT) {
            val (node, depth) = pending.removeFirst()
            visited++
            output.appendLine("node depth=$depth, class=${node.className}, id=${node.viewIdResourceName}, " +
                "text=${if (node.isPassword) "[redacted]" else text(node.text)}, description=${text(node.contentDescription)}, " +
                "visible=${node.isVisibleToUser}, focused=${node.isFocused}, enabled=${node.isEnabled}, clickable=${node.isClickable}")
            if (depth >= MAX_DEPTH) continue
            val children = minOf(node.childCount, MAX_CHILDREN, MAX_NODES - visited - pending.size)
            for (index in 0 until children) node.getChild(index)?.let { pending.add(it to depth + 1) }
        }
        if (pending.isNotEmpty()) output.appendLine("node summary truncated")
    }

    private fun shell(automation: UiAutomation, command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(LOG_CHUNK)
        while (output.length < MAX_OUTPUT) {
            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT - output.length))
            if (count < 0) break
            output.append(buffer, 0, count)
        }
        output.toString()
    }

    private fun text(value: CharSequence?): String = value?.toString()?.replace('\n', ' ')?.take(MAX_TEXT).orEmpty()

    private fun log(label: String, section: String, value: String) {
        // 每个用例的 logcat 已被现有 Android CI 报告收集，截图路径供本地模拟器拉取
        for ((index, chunk) in value.take(MAX_OUTPUT).chunked(LOG_CHUNK).withIndex()) {
            Log.e(TAG, "$label [$section:$index] $chunk")
        }
    }

    private fun logCaptureError(label: String, section: String, failure: Throwable) {
        runCatching { Log.e(TAG, "$label [$section] diagnostic unavailable: ${failure.javaClass.name}: ${text(failure.message)}") }
    }
}
