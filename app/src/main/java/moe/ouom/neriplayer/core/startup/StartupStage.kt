package moe.ouom.neriplayer.core.startup

internal enum class StartupStage {
    Loading,
    Disclaimer,
    Onboarding,
    Main
}

internal const val STARTUP_LOADING_INDICATOR_DELAY_MILLIS = 1_000L

internal fun shouldShowStartupLoadingIndicator(elapsedMillis: Long): Boolean {
    return elapsedMillis >= STARTUP_LOADING_INDICATOR_DELAY_MILLIS
}

internal fun shouldKeepSystemSplash(stage: StartupStage): Boolean {
    return stage == StartupStage.Loading
}
