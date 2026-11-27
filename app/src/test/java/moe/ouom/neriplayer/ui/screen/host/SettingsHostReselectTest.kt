package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHostReselectTest {
    private class RecordingStateHolder : SaveableStateHolder {
        val removedKeys = mutableListOf<Any>()

        @Composable
        override fun SaveableStateProvider(key: Any, content: @Composable () -> Unit) = content()

        override fun removeState(key: Any) {
            removedKeys += key
        }
    }

    @Test
    fun `reselect from a download page drops the restored settings sub page`() {
        for (state in listOf(SettingsScreenState.DownloadManager, SettingsScreenState.DownloadProgress)) {
            val holder = RecordingStateHolder()
            var returned = 0
            var dispatched = 0

            reselectSettingsHost(
                requestedState = state,
                saveableStateHolder = holder,
                returnToSettings = { returned++ },
                dispatchToSettingsRoot = { dispatched++ }
            )

            assertEquals(listOf<Any>(SettingsScreenState.Settings.saveableKey()), holder.removedKeys)
            assertEquals(1, returned)
            assertEquals(0, dispatched)
        }
    }

    @Test
    fun `reselect on the settings page keeps its state and lets it handle the tap`() {
        val holder = RecordingStateHolder()
        var returned = 0
        var dispatched = 0

        reselectSettingsHost(
            requestedState = SettingsScreenState.Settings,
            saveableStateHolder = holder,
            returnToSettings = { returned++ },
            dispatchToSettingsRoot = { dispatched++ }
        )

        assertEquals(emptyList<Any>(), holder.removedKeys)
        assertEquals(0, returned)
        assertEquals(1, dispatched)
    }
}
