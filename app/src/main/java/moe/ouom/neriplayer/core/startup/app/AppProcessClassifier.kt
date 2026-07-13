package moe.ouom.neriplayer.core.startup.app

internal object AppProcessClassifier {
    fun isMainProcess(
        currentProcessName: String,
        configuredMainProcessName: String?,
        packageName: String
    ): Boolean {
        val mainProcessName = configuredMainProcessName
            ?.takeIf { it.isNotBlank() }
            ?: packageName
        return currentProcessName == mainProcessName
    }
}
