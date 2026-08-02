package moe.ouom.neriplayer.navigation

const val ACTION_LAUNCHER_SHORTCUT_CONTINUE_PLAYBACK =
    "moe.ouom.neriplayer.action.CONTINUE_PLAYBACK"
const val ACTION_LAUNCHER_SHORTCUT_EXPLORE =
    "moe.ouom.neriplayer.action.OPEN_EXPLORE"
const val ACTION_LAUNCHER_SHORTCUT_LIBRARY =
    "moe.ouom.neriplayer.action.OPEN_LIBRARY"
const val ACTION_LAUNCHER_SHORTCUT_SHUFFLE_FAVORITES =
    "moe.ouom.neriplayer.action.SHUFFLE_FAVORITES"

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

fun launcherShortcutActionFromIntentAction(action: String?): LauncherShortcutAction? {
    return when (action) {
        ACTION_LAUNCHER_SHORTCUT_CONTINUE_PLAYBACK ->
            LauncherShortcutAction.ContinuePlayback
        ACTION_LAUNCHER_SHORTCUT_EXPLORE ->
            LauncherShortcutAction.OpenExplore
        ACTION_LAUNCHER_SHORTCUT_LIBRARY ->
            LauncherShortcutAction.OpenLibrary
        ACTION_LAUNCHER_SHORTCUT_SHUFFLE_FAVORITES ->
            LauncherShortcutAction.ShuffleFavorites
        else -> null
    }
}

fun launcherShortcutMainTabRoute(action: LauncherShortcutAction): String? {
    return when (action) {
        LauncherShortcutAction.OpenExplore -> Destinations.Explore.route
        LauncherShortcutAction.OpenLibrary -> Destinations.Library.route
        LauncherShortcutAction.ContinuePlayback,
        LauncherShortcutAction.ShuffleFavorites -> null
    }
}
