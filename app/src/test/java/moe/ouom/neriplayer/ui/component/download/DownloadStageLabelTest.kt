package moe.ouom.neriplayer.ui.component.download

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.download.DownloadStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStageLabelTest {

    @Test
    fun `waiting and processing stages describe what the task is doing`() {
        val expected = mapOf(
            DownloadStage.WAITING_HOST to CoreCommonR.string.download_waiting_host,
            DownloadStage.WAITING_DELETE_CLEANUP to CoreCommonR.string.download_waiting_delete_cleanup,
            DownloadStage.RESOLVING_SOURCE to CoreCommonR.string.download_resolving_source,
            DownloadStage.PREPARING_STORAGE to CoreCommonR.string.download_preparing_storage,
            DownloadStage.VERIFYING_AUDIO to CoreCommonR.string.download_verifying_audio,
            DownloadStage.COMMITTING_CORE to CoreCommonR.string.download_committing_core,
            DownloadStage.ASSETS_ENRICHING to CoreCommonR.string.download_assets_enriching,
            DownloadStage.WAITING_RETRY to CoreCommonR.string.download_waiting_retry
        )

        expected.forEach { (stage, label) ->
            assertEquals(stage.name, label, downloadStageLabelResource(stage))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun `transfer stages show byte progress instead of a stage label`() {
        assertNull(downloadStageLabelResource(DownloadStage.TRANSFERRING))
        assertNull(downloadStageLabelResource(DownloadStage.FINALIZING))
    }
}
