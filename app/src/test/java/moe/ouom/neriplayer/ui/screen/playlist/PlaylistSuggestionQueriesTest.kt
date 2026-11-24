package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.platform.youtube.settings.PlaylistSuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSuggestionQueriesTest {
    @Test
    fun eachModeUsesOnlySelectedSignalsAndKeepsRequestsBounded() {
        val artists = listOf("B", "A", "A", "C", "")
        assertEquals(listOf("Jazz"), playlistSuggestionQueries(" Jazz ", artists, PlaylistSuggestionSource.Title))
        assertEquals(listOf("A", "B"), playlistSuggestionQueries("Jazz", artists, PlaylistSuggestionSource.Tracks))
        assertEquals(listOf("Jazz", "A", "B"), playlistSuggestionQueries("Jazz", artists, PlaylistSuggestionSource.Both))
        assertEquals(emptyList<String>(), playlistSuggestionQueries(" ", emptyList(), PlaylistSuggestionSource.Both))
    }
}
