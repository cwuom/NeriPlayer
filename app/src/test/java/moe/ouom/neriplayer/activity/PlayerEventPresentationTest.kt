package moe.ouom.neriplayer.activity

import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEventPresentationTest {

    @Test
    fun `login prompts and errors stay modal while notices auto dismiss`() {
        assertEquals(
            PlayerEventPresentation.Dialog("login"),
            playerEventPresentation(PlayerEvent.ShowLoginPrompt("login"))
        )
        assertEquals(
            PlayerEventPresentation.Dialog("failed"),
            playerEventPresentation(PlayerEvent.ShowError("failed"))
        )
        assertEquals(
            PlayerEventPresentation.Notice("preview only"),
            playerEventPresentation(PlayerEvent.ShowNotice("preview only"))
        )
    }
}
