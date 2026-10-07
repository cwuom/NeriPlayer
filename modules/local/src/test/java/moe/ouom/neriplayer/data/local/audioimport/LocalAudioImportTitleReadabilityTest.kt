package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioImportTitleReadabilityTest {
    @Test
    fun `scanned titles must be real text rather than uris or placeholders`() {
        assertEquals(EXPECTED, TITLES.map { LocalAudioImportManager.isReadableScannedTitle(it) })
    }

    @Test
    fun `quick imported titles follow the same readability rules`() {
        assertEquals(EXPECTED, TITLES.map { LocalAudioImportManager.isReadableQuickImportedTitle(it) })
    }

    private companion object {
        val TITLES = listOf(
            null,
            "   ",
            " content://media/external/audio/media/1",
            "FILE:///music/a.flac",
            " Unknown Artist ",
            "未知",
            "  Night Drive "
        )
        val EXPECTED = listOf(false, false, false, false, false, false, true)
    }
}
