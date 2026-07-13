package moe.ouom.neriplayer.core.startup.app

internal object WebViewDataDirectorySuffix {
    private val unsafeCharacters = Regex("[^A-Za-z0-9_.-]")

    fun forProcess(processName: String): String {
        return processName
            .substringAfter(':', missingDelimiterValue = processName)
            .ifBlank { "webview" }
            .replace(unsafeCharacters, "_")
    }
}
