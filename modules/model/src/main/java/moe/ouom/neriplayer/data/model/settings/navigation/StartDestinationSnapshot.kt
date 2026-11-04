package moe.ouom.neriplayer.data.model.settings.navigation

const val DEFAULT_START_DESTINATION = "home"

data class StartDestinationSnapshot(
    val startDestination: String = DEFAULT_START_DESTINATION
)
