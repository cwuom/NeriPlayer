package moe.ouom.neriplayer.testing

import android.app.UiAutomation
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

internal class DocumentsPickerAutomation(private val directoryName: String) {
    private var lastClick: String? = null
    private val clickedButtons = mutableSetOf<String>()
    private var windows: List<String> = emptyList()

    fun confirm(automation: UiAutomation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val roots = (listOfNotNull(automation.rootInActiveWindow) +
            automation.windows.mapNotNull { it.root }).distinctBy { it.windowId }
        windows = roots.map { root ->
            val buttons = buttonIds.flatMap { id ->
                root.findAccessibilityNodeInfosByViewId(id).map {
                    "$id:${it.text}, enabled=${it.isEnabled}, visible=${it.isVisibleToUser}"
                }
            }
            val messages = root.findAccessibilityNodeInfosByViewId("android:id/message")
                .map { it.text?.take(300) }
            "${root.windowId}:${root.packageName}, buttons=$buttons, messages=$messages"
        }
        for (root in roots) {
            // 选择页和确认弹窗都应包含本次唯一目录，不能仅用根节点包名判断窗口归属
            if (root.findAccessibilityNodeInfosByText(directoryName).none { it.isVisibleToUser }) continue
            for (id in buttonIds) {
                val button = root.findAccessibilityNodeInfosByViewId(id).firstOrNull {
                    it.isVisibleToUser && it.isEnabled && it.isClickable
                } ?: continue
                val key = "${root.windowId}:$id:${button.text}"
                if (key in clickedButtons) continue
                if (button.refresh() && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    // android:id/button1 也可能是选择页按钮，等待确认弹窗后再点击
                    lastClick = key
                    clickedButtons += key
                    return
                }
            }
        }
    }

    fun diagnostics(): String = "windows=$windows, lastClick=$lastClick"

    private companion object {
        val buttonIds = listOf(
            "com.android.documentsui:id/action_menu_select",
            "com.google.android.documentsui:id/action_menu_select",
            "android:id/button1"
        )
    }
}
