package moe.ouom.neriplayer.ui.viewmodel

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock

class BackupRestoreUninitializedActionsTest {

    private val viewModel = BackupRestoreViewModel { error("playlist counts are not observed before initialization") }

    @Test
    fun `export and import requests before initialization leave the ui state untouched`() {
        val initial = viewModel.uiState.value

        viewModel.exportPlaylists(mock(Uri::class.java))
        viewModel.importPlaylists(mock(Uri::class.java))

        assertEquals(BackupRestoreUiState(), initial)
        assertSame(initial, viewModel.uiState.value)
    }

    @Test
    fun `the default backup file name is used before initialization`() {
        assertEquals("neriplayer_backup.json", viewModel.generateBackupFileName())
    }
}
