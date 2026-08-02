package moe.ouom.neriplayer.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherShortcutsTest {
    @Test
    fun `known shortcut actions map to requests`() {
        assertEquals(
            LauncherShortcutAction.ContinuePlayback,
            launcherShortcutActionFromIntentAction(
                ACTION_LAUNCHER_SHORTCUT_CONTINUE_PLAYBACK
            )
        )
        assertEquals(
            LauncherShortcutAction.OpenExplore,
            launcherShortcutActionFromIntentAction(ACTION_LAUNCHER_SHORTCUT_EXPLORE)
        )
        assertEquals(
            LauncherShortcutAction.OpenLibrary,
            launcherShortcutActionFromIntentAction(ACTION_LAUNCHER_SHORTCUT_LIBRARY)
        )
        assertEquals(
            LauncherShortcutAction.ShuffleFavorites,
            launcherShortcutActionFromIntentAction(
                ACTION_LAUNCHER_SHORTCUT_SHUFFLE_FAVORITES
            )
        )
    }

    @Test
    fun `unknown shortcut action is ignored`() {
        assertNull(launcherShortcutActionFromIntentAction(null))
        assertNull(launcherShortcutActionFromIntentAction("android.intent.action.MAIN"))
    }

    @Test
    fun `tab shortcuts resolve to main tab routes only`() {
        assertEquals(
            Destinations.Explore.route,
            launcherShortcutMainTabRoute(LauncherShortcutAction.OpenExplore)
        )
        assertEquals(
            Destinations.Library.route,
            launcherShortcutMainTabRoute(LauncherShortcutAction.OpenLibrary)
        )
        assertNull(
            launcherShortcutMainTabRoute(LauncherShortcutAction.ContinuePlayback)
        )
        assertNull(
            launcherShortcutMainTabRoute(LauncherShortcutAction.ShuffleFavorites)
        )
    }
}
