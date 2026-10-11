package moe.ouom.neriplayer.ui.screen.tab.library

import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class LocalArtistLibraryGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `tablet windows use the home sized artist cells`() {
        assertEquals(120.dp, localArtistGridMinCellSize(360.dp))
        assertEquals(120.dp, localArtistGridMinCellSize(719.dp))
        assertEquals(156.dp, localArtistGridMinCellSize(720.dp))
        assertEquals(156.dp, localArtistGridMinCellSize(1280.dp))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet artist cards are at least the tablet cell width`() {
        var clicked: LocalArtistSummary? = null
        val artist = LocalArtistSummary(name = "Artist A", songs = emptyList())
        composeRule.setContent {
            LocalArtistLibraryGrid(
                artists = listOf(artist),
                onClick = { clicked = it },
                offlineMode = true
            )
        }

        val card = composeRule.onNodeWithText("Artist A").getUnclippedBoundsInRoot()
        assertTrue("card width ${card.width}", card.width >= 156.dp)
        composeRule.onNodeWithText("Artist A").performClick()
        assertEquals(artist, clicked)
    }

    @Test
    fun `empty library shows the empty hint`() {
        composeRule.setContent {
            LocalArtistLibraryGrid(artists = emptyList(), onClick = {}, offlineMode = true)
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.library_local_artist_empty))
            .assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.library_local_artist_hint))
            .assertExists()
    }
}
