package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricMatchFeedbackKind
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricMatchFeedbackPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricMatchQueryPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorActionPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorHeaderPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorSongIdentityPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricMatchFeedbackPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricMatchQueryPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricsEditorActionPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricsEditorHeaderPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricsEditorSongIdentityPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsEditorPresentationTest {
    private val song = SongItem(
        id = 493L,
        name = "Original title",
        artist = "Original artist",
        album = "Synthetic album",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null
    )

    @Test
    fun `expanded header omits inline metadata and match while preserving cancellation availability`() {
        assertEquals(
            LyricsEditorHeaderPresentation(null, true, false),
            resolveLyricsEditorHeaderPresentation(null, isSaving = false)
        )
        assertEquals(
            LyricsEditorHeaderPresentation(null, false, true),
            resolveLyricsEditorHeaderPresentation(null, isSaving = true)
        )
    }

    @Test
    fun `song identity resolves each custom field independently and preserves empty overrides`() {
        val cases = listOf(
            Triple(song, "Original title", "Original artist"),
            Triple(song.copy(customName = "Custom title"), "Custom title", "Original artist"),
            Triple(song.copy(customArtist = "Custom artist"), "Original title", "Custom artist"),
            Triple(song.copy(customName = "Custom title", customArtist = "Custom artist"), "Custom title", "Custom artist"),
            Triple(song.copy(customName = ""), "", "Original artist"),
            Triple(song.copy(customArtist = ""), "Original title", ""),
            Triple(song.copy(customName = "", customArtist = ""), "", "")
        )
        cases.forEach { (candidate, title, artist) ->
            assertEquals(
                LyricsEditorSongIdentityPresentation(title, artist),
                resolveLyricsEditorSongIdentityPresentation(candidate)
            )
        }
    }

    @Test
    fun `compact summary uses each custom value independently and falls back only for null`() {
        val cases = listOf(
            song to "Original title · Original artist",
            song.copy(customName = "Custom title") to "Custom title · Original artist",
            song.copy(customArtist = "Custom artist") to "Original title · Custom artist",
            song.copy(customName = "Custom title", customArtist = "Custom artist") to
                "Custom title · Custom artist",
            song.copy(customName = "") to " · Original artist",
            song.copy(customArtist = "") to "Original title · ",
            song.copy(customName = "", customArtist = "") to " · "
        )
        cases.forEach { (candidate, summary) ->
            val presentation = resolveLyricsEditorHeaderPresentation(candidate, isSaving = false)
            assertEquals(summary, presentation.songIdentity?.summary)
            assertEquals(true, presentation.actionsEnabled)
            assertEquals(false, presentation.saving)
        }
    }

    @Test
    fun `saving keeps compact metadata and match placement but locks header actions`() {
        assertEquals(
            LyricsEditorHeaderPresentation(
                songIdentity = LyricsEditorSongIdentityPresentation("Original title", "Original artist"),
                actionsEnabled = false,
                saving = true
            ),
            resolveLyricsEditorHeaderPresentation(song, isSaving = true)
        )
    }

    @Test
    fun `saving disables editor actions and selects their progress state`() {
        assertEquals(
            LyricsEditorActionPresentation(enabled = true, saving = false),
            resolveLyricsEditorActionPresentation(isSaving = false)
        )
        assertEquals(
            LyricsEditorActionPresentation(enabled = false, saving = true),
            resolveLyricsEditorActionPresentation(isSaving = true)
        )
    }

    @Test
    fun `search availability covers loading blank queries and selected source combinations`() {
        val source = setOf(EditableLyricMatchSource.KUGOU)
        val cases = listOf(
            QueryCase("  title  ", false, source, true),
            QueryCase("  title  ", false, emptySet(), false),
            QueryCase("  title  ", true, source, false),
            QueryCase("  title  ", true, emptySet(), false),
            QueryCase("\t\n", false, source, false),
            QueryCase("\t\n", false, emptySet(), false),
            QueryCase("\t\n", true, source, false),
            QueryCase("\t\n", true, emptySet(), false)
        )
        cases.forEach { case ->
            assertEquals(
                case.toString(),
                LyricMatchQueryPresentation(case.query, case.enabled),
                resolveLyricMatchQueryPresentation(case.query, case.loading, case.sources)
            )
        }
    }

    @Test
    fun `query presentation preserves empty whitespace and nonblank input without trimming`() {
        val source = setOf(EditableLyricMatchSource.KUGOU)
        listOf("", " ", "\t\n", "\u3000").forEach { query ->
            assertEquals(
                LyricMatchQueryPresentation(query, false),
                resolveLyricMatchQueryPresentation(query, isLoading = false, selectedSources = source)
            )
        }
        assertEquals(
            LyricMatchQueryPresentation("\tTitle\n", true),
            resolveLyricMatchQueryPresentation("\tTitle\n", isLoading = false, selectedSources = source)
        )
    }

    @Test
    fun `loading and error feedback coexist in that order even for an empty error message`() {
        listOf("", " ", "Network failure").forEach { message ->
            for (hasSearched in listOf(false, true)) {
                for (resultsEmpty in listOf(false, true)) {
                    assertEquals(
                        LyricMatchFeedbackPresentation(
                            listOf(LyricMatchFeedbackKind.LOADING, LyricMatchFeedbackKind.ERROR), message
                        ),
                        resolveLyricMatchFeedbackPresentation(true, message, hasSearched, resultsEmpty)
                    )
                }
            }
        }
    }

    @Test
    fun `loading without an error suppresses empty feedback until completion`() {
        for (hasSearched in listOf(false, true)) {
            for (resultsEmpty in listOf(false, true)) {
                assertEquals(
                    LyricMatchFeedbackPresentation(listOf(LyricMatchFeedbackKind.LOADING), null),
                    resolveLyricMatchFeedbackPresentation(true, null, hasSearched, resultsEmpty)
                )
            }
        }
    }

    @Test
    fun `any nonnull error suppresses empty feedback and retains its exact text`() {
        listOf("", " ", "Network failure").forEach { message ->
            for (hasSearched in listOf(false, true)) {
                for (resultsEmpty in listOf(false, true)) {
                    assertEquals(
                        LyricMatchFeedbackPresentation(listOf(LyricMatchFeedbackKind.ERROR), message),
                        resolveLyricMatchFeedbackPresentation(false, message, hasSearched, resultsEmpty)
                    )
                }
            }
        }
    }

    @Test
    fun `empty feedback requires a completed search with no results and no error`() {
        val cases = listOf(
            FeedbackCase(false, false, emptyList()),
            FeedbackCase(false, true, emptyList()),
            FeedbackCase(true, false, emptyList()),
            FeedbackCase(true, true, listOf(LyricMatchFeedbackKind.EMPTY))
        )
        cases.forEach { case ->
            assertEquals(
                case.toString(),
                LyricMatchFeedbackPresentation(case.expected, null),
                resolveLyricMatchFeedbackPresentation(false, null, case.hasSearched, case.resultsEmpty)
            )
        }
    }

    private data class QueryCase(
        val query: String,
        val loading: Boolean,
        val sources: Set<EditableLyricMatchSource>,
        val enabled: Boolean
    )

    private data class FeedbackCase(
        val hasSearched: Boolean,
        val resultsEmpty: Boolean,
        val expected: List<LyricMatchFeedbackKind>
    )
}
