package moe.ouom.neriplayer.ui.screen.tab

import android.content.Context
import android.app.Activity
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.BackupImportRecreateAction
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.recreateSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class SettingsBackupTransferControllerTest {
    @Test
    fun `recreate action only recreates activity contexts`() {
        val activity = mock(Activity::class.java)
        val applicationContext = mock(Context::class.java)

        recreateSettingsActivity(activity)
        recreateSettingsActivity(applicationContext)

        verify(activity).recreate()
        verifyNoInteractions(applicationContext)
    }

    @Test
    fun `configuration import consumes restart request before activity recreation`() {
        val context = mock(Context::class.java)
        val events = mutableListOf<String>()
        val action = BackupImportRecreateAction(
            required = true,
            context = context,
            onBeforeLanguageRestart = { events += "before" },
            onConsumeRequest = { events += "consume" },
            onRecreateActivity = { target ->
                assertEquals(context, target)
                events += "recreate"
            }
        )

        action.applyIfRequired()

        assertEquals(listOf("before", "consume", "recreate"), events)
    }

    @Test
    fun `inactive import request has no restart side effects`() {
        val action = BackupImportRecreateAction(
            required = false,
            context = mock(Context::class.java),
            onBeforeLanguageRestart = { error("unexpected restart") },
            onConsumeRequest = { error("unexpected consume") },
            onRecreateActivity = { error("unexpected recreation") }
        )

        action.applyIfRequired()
    }
}
