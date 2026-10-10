package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import android.content.res.Resources
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.backup.BackupManager
import moe.ouom.neriplayer.data.model.config.AppConfigImportResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class TransferSummaryStringsTest {

    private val resources: Resources = mock(Resources::class.java) { invocation ->
        if (invocation.method.name == "getQuantityString") {
            "q${invocation.arguments[0]}=${invocation.arguments[1]}"
        } else {
            null
        }
    }
    private val context: Context = mock(Context::class.java) { invocation ->
        when (invocation.method.name) {
            "getString" -> "s" + invocation.arguments.joinToString("|")
            "getResources" -> resources
            else -> null
        }
    }

    @Test
    fun `playlist import summary lists only non empty merge and skip counts`() {
        val strings = BackupRestoreViewModel.BackupRestoreStrings.from(context)

        val plain = strings.importSummary(importResult(merged = 0, skipped = 0))
        val detailed = strings.importSummary(importResult(merged = 2, skipped = 1))

        assertEquals(
            listOf(
                "s${CoreCommonR.string.playlist_import_complete}",
                "q${CoreCommonR.plurals.playlist_import_count}=3",
                "s${CoreCommonR.string.playlist_backup_date}|2026-10-01"
            ),
            plain.lines()
        )
        assertEquals(
            listOf(
                "s${CoreCommonR.string.playlist_import_complete}",
                "q${CoreCommonR.plurals.playlist_import_count}=3",
                "q${CoreCommonR.plurals.playlist_merge_count}=2",
                "q${CoreCommonR.plurals.playlist_skip_count}=1",
                "s${CoreCommonR.string.playlist_backup_date}|2026-10-01"
            ),
            detailed.lines()
        )
    }

    @Test
    fun `config import summary lists restored counts`() {
        val strings = ConfigTransferViewModel.ConfigTransferStrings.from(context)

        val summary = strings.importSuccess(AppConfigImportResult(4, 3, 2, 1))

        assertEquals(
            listOf(
                "s${CoreCommonR.string.settings_config_import_success}",
                "q${CoreCommonR.plurals.settings_config_import_restored_settings}=4",
                "q${CoreCommonR.plurals.settings_config_import_restored_listen_together}=3",
                "q${CoreCommonR.plurals.settings_config_import_restored_auth}=2",
                "q${CoreCommonR.plurals.settings_config_import_restored_sync}=1"
            ),
            summary.lines()
        )
    }

    @Test
    fun `config import summary appends warnings and restart hint`() {
        val strings = ConfigTransferViewModel.ConfigTransferStrings.from(context)

        val summary = strings.importSuccess(
            AppConfigImportResult(
                restoredSettingsCount = 1,
                restoredListenTogetherCount = 0,
                restoredAuthCount = 0,
                restoredSyncCount = 0,
                warnings = listOf("token skipped", "theme reset"),
                requiresActivityRecreate = true
            )
        ).lines()

        assertEquals(
            listOf(
                "",
                "s${CoreCommonR.string.settings_config_import_warning_title}",
                "- token skipped",
                "- theme reset",
                "",
                "s${CoreCommonR.string.settings_config_import_restart_hint}"
            ),
            summary.drop(5)
        )
    }

    private fun importResult(merged: Int, skipped: Int) = BackupManager.ImportResult(
        importedCount = 3,
        skippedCount = skipped,
        mergedCount = merged,
        totalCount = 3 + merged + skipped,
        backupDate = "2026-10-01"
    )
}
