package moe.ouom.neriplayer.ui.screen.artist

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistHeaderTextNormalizationTest {

    @Test
    fun `missing bilibili backdrop urls become empty sources`() {
        assertEquals(BiliUploaderBackdropSources(bannerUrl = "", avatarUrl = ""), resolveBiliUploaderBackdropSources(null, null))
    }

    @Test
    fun `bilibili backdrop urls are trimmed independently`() {
        assertEquals(
            BiliUploaderBackdropSources(bannerUrl = "https://example.invalid/banner.jpg", avatarUrl = ""),
            resolveBiliUploaderBackdropSources("  https://example.invalid/banner.jpg ", null)
        )
        assertEquals(
            BiliUploaderBackdropSources(bannerUrl = "", avatarUrl = "https://example.invalid/face.jpg"),
            resolveBiliUploaderBackdropSources(null, "\thttps://example.invalid/face.jpg\n")
        )
    }

    @Test
    fun `creator items title joins creator and loaded section title`() {
        assertEquals(
            "Creator · Loaded",
            resolveYouTubeMusicCreatorItemsTitle(creatorName = " Creator ", sectionTitle = "Section", loadedTitle = " Loaded ")
        )
    }

    @Test
    fun `creator items title falls back to the section title when nothing was loaded`() {
        assertEquals(
            "Section",
            resolveYouTubeMusicCreatorItemsTitle(creatorName = "   ", sectionTitle = " Section ", loadedTitle = "")
        )
    }

    @Test
    fun `creator items title drops blank section titles and duplicate parts`() {
        assertEquals(
            "Creator",
            resolveYouTubeMusicCreatorItemsTitle(creatorName = "Creator", sectionTitle = "  ", loadedTitle = " ")
        )
        assertEquals(
            "Same",
            resolveYouTubeMusicCreatorItemsTitle(creatorName = "Same", sectionTitle = "Section", loadedTitle = "Same ")
        )
        assertEquals("", resolveYouTubeMusicCreatorItemsTitle(creatorName = "", sectionTitle = "", loadedTitle = ""))
    }
}
