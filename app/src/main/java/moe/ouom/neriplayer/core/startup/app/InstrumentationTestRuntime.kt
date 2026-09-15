package moe.ouom.neriplayer.core.startup.app

internal object InstrumentationTestRuntime {
    @Volatile
    var isActive = false
        private set

    // runner 必须在 Application 创建前标记，避免后台预热启动 WebView 干扰 UI 测试
    fun markActive() {
        isActive = true
    }
}
