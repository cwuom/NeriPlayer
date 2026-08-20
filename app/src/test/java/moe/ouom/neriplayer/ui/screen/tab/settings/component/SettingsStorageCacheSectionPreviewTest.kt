package moe.ouom.neriplayer.ui.screen.tab.settings.component

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStorageCacheSectionPreviewTest {

    @Test
    fun `file name preview renders remote identity placeholders`() {
        assertEquals(
            "123456 - 456789 - 789012",
            buildDownloadFileNameTemplatePreview("%id% - %audioId% - %subAudioId%")
        )
    }
}
