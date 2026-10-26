package moe.ouom.neriplayer.ui.viewmodel

import android.content.res.Resources
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.config.AppConfigImportResult
import moe.ouom.neriplayer.data.model.config.AppConfigImportWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock

class TransferFeedbackLocaleTest {
    @Test
    fun `existing export feedback resolves in the new language without recreating its state`() {
        val strings = BackupRestoreViewModel.BackupRestoreStrings.create()
        val feedback = strings.exportSuccess("backup.json")

        assertEquals("导出成功: backup.json", feedback.resolve(resources("导出成功")))
        assertEquals("Exported: backup.json", feedback.resolve(resources("Exported")))
    }

    @Test
    fun `configuration failure keeps the cause while the prefix follows the current language`() {
        val strings = ConfigTransferViewModel.ConfigTransferStrings.create()
        val feedback = strings.exportFailed("HTTP 503")

        assertEquals("导出失败: HTTP 503", feedback.resolve(resources("导出失败")))
        assertEquals("Export failed: HTTP 503", feedback.resolve(resources("Export failed")))
    }

    @Test
    fun `existing config warnings resolve by type while external text remains unchanged`() {
        val feedback = ConfigTransferViewModel.ConfigTransferStrings.create().importSuccess(
            AppConfigImportResult(
                0, 0, 0, 0,
                warnings = listOf("导入时的旧提示", "外部原文"),
                warningCodes = mapOf(0 to AppConfigImportWarning.INVALID_SETTING_VALUES)
            )
        )
        fun warningResources(message: String): Resources = mock(Resources::class.java) { invocation ->
            when (invocation.method.name) {
                "getString" -> when (invocation.arguments[0]) {
                    AppR.string.feedback_lines -> invocation.arguments.drop(1).joinToString("\n")
                    AppR.string.settings_config_import_warning_item -> "- ${invocation.arguments[1]}"
                    CoreCommonR.string.config_import_warning_invalid_setting_values -> message
                    else -> "summary"
                }
                "getQuantityString" -> "count"
                else -> null
            }
        }
        for (message in listOf("已调整无效设置", "Invalid settings adjusted", "已调整无效设置")) {
            val resolved = feedback.resolve(warningResources(message))
            assertTrue(resolved.lines().contains("- $message"))
            assertTrue(resolved.lines().contains("- 外部原文"))
            assertFalse(resolved.contains("导入时的旧提示"))
        }
    }

    private fun resources(prefix: String): Resources = mock(Resources::class.java) { invocation ->
        when (invocation.method.name) {
            "getString" -> when (invocation.arguments[0]) {
                AppR.string.feedback_value -> "%1\$s: %2\$s".format(
                    *invocation.arguments.drop(1).toTypedArray()
                )
                CoreCommonR.string.playlist_export_success,
                CoreCommonR.string.settings_config_export_failed -> prefix
                else -> error("Unexpected feedback resource ${invocation.arguments[0]}")
            }
            else -> null
        }
    }
}
