package moe.ouom.neriplayer.data.model.navigation

enum class LauncherShortcutAction {
    ContinuePlayback,
    OpenExplore,
    OpenLibrary,
    ShuffleFavorites
}

data class LauncherShortcutRequest(
    val token: Long,
    val action: LauncherShortcutAction
)
