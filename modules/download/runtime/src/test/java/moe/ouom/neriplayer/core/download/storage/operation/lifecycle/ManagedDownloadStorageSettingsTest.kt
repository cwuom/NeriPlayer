package moe.ouom.neriplayer.core.download.storage.operation.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageSettingsTest {
    private val settings = ManagedDownloadStorageSettings { "/downloads" }

    @Test
    fun `returning to an earlier file name template uses a fresh revision`() {
        settings.prime(null, null, "first-%title%")
        val first = settings.fileNameTemplateState.value

        settings.updateFileNameTemplate("second-%title%")
        val second = settings.fileNameTemplateState.value
        assertEquals("second-%title%", second.template)
        assertTrue(second.revision > first.revision)

        settings.updateFileNameTemplate("first-%title%")
        val restored = settings.fileNameTemplateState.value
        assertEquals("first-%title%", restored.template)
        assertTrue(restored.revision > second.revision)
        assertEquals(restored.template, settings.fileNameTemplate)
    }

    @Test
    fun `equivalent normalized templates retain the current revision`() {
        settings.updateFileNameTemplate("  %title%  ")
        val current = settings.fileNameTemplateState.value
        assertEquals("%title%", current.template)

        settings.prime(null, null, "%title%")
        assertEquals(current, settings.fileNameTemplateState.value)

        settings.updateFileNameTemplate(null)
        val cleared = settings.fileNameTemplateState.value
        assertTrue(cleared.revision > current.revision)
        settings.updateFileNameTemplate("   ")
        assertEquals(cleared, settings.fileNameTemplateState.value)
    }
}
