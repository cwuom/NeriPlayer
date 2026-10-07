package moe.ouom.neriplayer.ui.screen.tab.settings.page

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchHighlightRedirectTest {

    @Test
    fun `dynamic color only redirects the seed color control on the theme page`() {
        val seedOutsideTheme = entry(page = SettingsPage.Personalization, targetId = "manual:theme_seed_color")

        assertEquals(
            "manual:theme_seed_color",
            resolveSettingsSearchHighlightTarget(seedOutsideTheme, dynamicColor = true)
        )
    }

    @Test
    fun `following the default quality keeps unrelated audio quality controls highlighted`() {
        val downloadQuality = entry(
            page = SettingsPage.AudioQuality,
            targetId = "setting:download_follow_playback_audio_quality"
        )

        assertEquals(
            "setting:download_follow_playback_audio_quality",
            resolveSettingsSearchHighlightTarget(
                entry = downloadQuality,
                dynamicColor = false,
                mobileDataFollowDefaultAudioQuality = true
            )
        )
    }

    @Test
    fun `missing custom background keeps controls that do not depend on the image`() {
        val defaultStart = entry(
            page = SettingsPage.Personalization,
            targetId = "setting:default_start_destination"
        )

        assertEquals(
            "setting:default_start_destination",
            resolveSettingsSearchHighlightTarget(
                entry = defaultStart,
                dynamicColor = false,
                hasCustomBackground = false
            )
        )
    }

    @Test
    fun `missing custom background redirects the blur control to the image picker`() {
        val blur = entry(page = SettingsPage.Personalization, targetId = "setting:background_image_blur")

        assertEquals(
            "setting:background_image_uri",
            resolveSettingsSearchHighlightTarget(
                entry = blur,
                dynamicColor = true,
                mobileDataFollowDefaultAudioQuality = true,
                hasCustomBackground = false
            )
        )
    }

    private fun entry(page: SettingsPage, targetId: String) = SettingsSearchEntry(
        id = targetId,
        page = page,
        title = targetId,
        description = "",
        tokens = emptyList(),
        targetId = targetId,
        order = 0
    )
}
