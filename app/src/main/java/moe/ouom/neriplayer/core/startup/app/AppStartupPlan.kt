package moe.ouom.neriplayer.core.startup.app

internal data class AppStartupPlan(
    val runningInMainProcess: Boolean,
    val enterSafeMode: Boolean
) {
    val shouldCapturePreviousAnr: Boolean
        get() = runningInMainProcess

    val shouldInstallNativeCrashHandler: Boolean
        get() = runningInMainProcess && !enterSafeMode

    val shouldInitializeNormalComponents: Boolean
        get() = runningInMainProcess && !enterSafeMode
}

internal object AppStartupPlanner {
    fun plan(
        runningInMainProcess: Boolean,
        safeModeRequested: Boolean
    ): AppStartupPlan {
        return AppStartupPlan(
            runningInMainProcess = runningInMainProcess,
            enterSafeMode = runningInMainProcess && safeModeRequested
        )
    }
}
