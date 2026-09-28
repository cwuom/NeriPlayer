package moe.ouom.neriplayer.ui.screen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchCandidate
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchConfidence
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchRequest
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchSource
import moe.ouom.neriplayer.core.api.lyrics.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSource
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsDraft
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsEditorOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.defaultEditableLyricsMatchKeyword
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.editableLyricMatchFailureDescription
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.mergeEditableLyricMatchResults
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.stringResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsEditorOwnerTest {
    private val song = SongItem(
        id = 7L,
        name = "Title",
        artist = "Artist",
        album = "Album",
        albumId = 3L,
        durationMs = 120_000L,
        coverUrl = null
    )

    @Test
    fun `save locks duplicate actions and only dismisses after success`() = runTest {
        val pending = CompletableDeferred<Boolean>()
        val owner = owner { emptyList() }
        var savedCount = 0
        var failedCount = 0
        var dismissedCount = 0
        owner.save(false, { savedCount++; pending.await() }, { failedCount++ }, { dismissedCount++ })
        owner.save(true, { savedCount++ ; true }, { failedCount++ }, { dismissedCount++ })
        runCurrent()
        assertTrue(owner.isSavingState.value)
        assertEquals(1, savedCount)
        assertEquals(0, dismissedCount)
        owner.clearSelectedText()
        owner.pasteSelectedText("late edit")
        assertEquals("original", owner.lyricsTextState.value)
        pending.complete(false)
        advanceUntilIdle()
        assertFalse(owner.isSavingState.value)
        assertEquals(1, failedCount)
        assertEquals(0, dismissedCount)

        owner.save(true, { draft ->
            assertEquals(EditSongLyricsDraft("original", "translation", "romanized", true), draft)
            true
        }, { failedCount++ }, { dismissedCount++ })
        advanceUntilIdle()
        assertEquals(1, dismissedCount)
        owner.save(false, { throw IllegalStateException("write failed") }, { failedCount++ }, { dismissedCount++ })
        advanceUntilIdle()
        assertEquals(2, failedCount)
        assertEquals(1, dismissedCount)
        assertFalse(owner.isSavingState.value)
        owner.dispose()
    }

    @Test
    fun `query change cancels stale match and keeps new results`() = runTest {
        val firstResult = CompletableDeferred<List<RankedEditableLyricMatch>>()
        val requests = mutableListOf<EditableLyricMatchRequest>()
        val owner = owner { request ->
            requests += request
            if (request.keyword == "old") firstResult.await() else listOf(match("new"))
        }
        val sources = setOf(EditableLyricMatchSource.KUGOU)
        owner.runMatch("old", sources, true, "source required") { "failed" }
        runCurrent()
        assertTrue(owner.isMatchingState.value)
        owner.changeQuery("new")
        assertFalse(owner.isMatchingState.value)
        owner.runMatch("new", sources, false, "source required") { "failed" }
        advanceUntilIdle()
        assertEquals(listOf("old", "new"), requests.map { it.keyword })
        assertEquals(false, requests.last().preferWordTimed)
        assertEquals(listOf(match("new")), owner.resultsBySourceState.value[sources.single()])
        firstResult.complete(listOf(match("old")))
        advanceUntilIdle()
        assertEquals(listOf(match("new")), owner.resultsBySourceState.value[sources.single()])
        owner.dispose()
    }

    @Test
    fun `match source validation and cache retain previous results after empty refresh`() = runTest {
        var matches = listOf(match("first"))
        val owner = owner { matches }
        val source = EditableLyricMatchSource.KUGOU
        owner.runMatch("name", emptySet(), true, "source required") { "failed" }
        assertEquals("source required", owner.matchErrorState.value)
        owner.runMatch("name", setOf(source), true, "source required") { "failed" }
        advanceUntilIdle()
        assertEquals(listOf(match("first")), owner.resultsBySourceState.value[source])

        matches = emptyList()
        owner.runMatch("name", setOf(source), true, "source required") { "failed" }
        advanceUntilIdle()
        assertEquals(listOf(match("first")), owner.resultsBySourceState.value[source])
        owner.changeQuery("different")
        assertTrue(owner.resultsBySourceState.value.isEmpty())
        assertTrue(owner.searchedSourcesState.value.isEmpty())
        owner.dispose()
    }

    @Test
    fun `text actions affect selected tab and applying match keeps blank translation`() = runTest {
        val owner = owner { emptyList() }
        owner.selectedTabState.intValue = 1
        owner.clearSelectedText()
        assertEquals("", owner.translatedLyricsTextState.value)
        assertEquals("original", owner.lyricsTextState.value)
        owner.pasteSelectedText("pasted")
        assertEquals("pasted", owner.translatedLyricsTextState.value)
        owner.applyMatch(match("matched", translation = ""))
        assertEquals("matched", owner.lyricsTextState.value)
        assertEquals("pasted", owner.translatedLyricsTextState.value)
        assertEquals(0, owner.selectedTabState.intValue)
        owner.applyMatch(match("new match", translation = "new translation"))
        assertEquals("new translation", owner.translatedLyricsTextState.value)
        owner.selectedTabState.intValue = 2
        owner.replaceSelectedText("new romanized")
        assertEquals("new romanized", owner.romanizedLyricsTextState.value)
        owner.dispose()
    }

    @Test
    fun `save request chooses local confirmation and ignores requests while locked`() = runTest {
        val remoteOwner = owner { emptyList() }
        assertTrue(remoteOwner.requestSave(LyricsEditorSource.SIDECAR, false))
        remoteOwner.dispose()

        val localOwner = NowPlayingLyricsEditorOwner(
            song = song.copy(localFilePath = "/tmp/lyrics-editor-local.mp3"),
            initialLyrics = "original",
            initialTranslatedLyrics = "translation",
            initialRomanizedLyrics = "romanized",
            scope = this,
            matchLyrics = { emptyList() },
            matchDispatcher = StandardTestDispatcher(testScheduler)
        )
        assertFalse(localOwner.requestSave(LyricsEditorSource.EMBEDDED, true))
        assertTrue(localOwner.showEmbeddedLyricsOverwriteConfirmState.value)
        assertFalse(localOwner.requestSave(LyricsEditorSource.SIDECAR, false))
        assertTrue(localOwner.showLocalMetadataWriteBackConfirmState.value)
        val pending = CompletableDeferred<Boolean>()
        localOwner.save(false, { pending.await() }, {}, {})
        assertFalse(localOwner.requestSave(LyricsEditorSource.SIDECAR, false))
        pending.complete(true)
        advanceUntilIdle()
        localOwner.dispose()
    }

    @Test
    fun `match failure unlocks search and disposal cancels pending result`() = runTest {
        val failingOwner = owner { throw IllegalStateException("network") }
        val sources = setOf(EditableLyricMatchSource.KUGOU)
        failingOwner.runMatch("  ", sources, true, "source required") { "failed" }
        assertFalse(failingOwner.isMatchingState.value)
        failingOwner.runMatch("name", sources, true, "source required") { "failed: ${it.message}" }
        advanceUntilIdle()
        assertEquals("failed: network", failingOwner.matchErrorState.value)
        assertFalse(failingOwner.isMatchingState.value)
        failingOwner.dispose()

        val pending = CompletableDeferred<List<RankedEditableLyricMatch>>()
        val closingOwner = owner { pending.await() }
        closingOwner.runMatch("name", sources, true, "source required") { "failed" }
        runCurrent()
        closingOwner.runMatch("duplicate", sources, true, "source required") { "failed" }
        closingOwner.dispose()
        pending.complete(listOf(match("late")))
        advanceUntilIdle()
        assertTrue(closingOwner.resultsBySourceState.value.isEmpty())
    }

    @Test
    fun `back closes the top editor overlay before leaving the sheet`() = runTest {
        val owner = owner { emptyList() }
        var dismisses = 0
        owner.showMatchSheetState.value = true
        owner.handleBack { dismisses++ }
        assertFalse(owner.showMatchSheetState.value)
        owner.showLocalMetadataWriteBackConfirmState.value = true
        owner.handleBack { dismisses++ }
        assertFalse(owner.showLocalMetadataWriteBackConfirmState.value)
        owner.showEmbeddedLyricsOverwriteConfirmState.value = true
        owner.handleBack { dismisses++ }
        assertFalse(owner.showEmbeddedLyricsOverwriteConfirmState.value)
        assertEquals(0, dismisses)
        owner.handleBack { dismisses++ }
        assertEquals(1, dismisses)
        owner.dispose()
    }

    @Test
    fun `source selection toggles both ways and text states handle each tab`() = runTest {
        val owner = owner { emptyList() }
        val source = EditableLyricMatchSource.KUGOU
        val initiallySelected = source in owner.selectedSourcesState.value
        owner.toggleSource(source)
        assertEquals(!initiallySelected, source in owner.selectedSourcesState.value)
        owner.toggleSource(source)
        assertEquals(initiallySelected, source in owner.selectedSourcesState.value)
        owner.replaceSelectedText("first")
        assertEquals("first", owner.lyricsTextState.value)
        owner.selectedTabState.intValue = 1
        owner.replaceSelectedText("second")
        assertEquals("second", owner.translatedLyricsTextState.value)
        owner.selectedTabState.intValue = 2
        owner.clearSelectedText()
        assertEquals("", owner.romanizedLyricsTextState.value)
        owner.selectedTabState.intValue = 99
        owner.replaceSelectedText("ignored")
        assertEquals("first", owner.lyricsTextState.value)
        owner.dispose()
    }

    @Test
    fun `source and confidence labels cover every supported value`() {
        assertEquals(6, EditableLyricMatchSource.entries.map { it.stringResId() }.distinct().size)
        assertEquals(3, EditableLyricMatchConfidence.entries.map { it.stringResId() }.distinct().size)
    }

    @Test
    fun `default query and failures use edited display details and safe cause`() {
        assertEquals("Title Artist", defaultEditableLyricsMatchKeyword(song))
        assertEquals(
            "Edited Singer",
            defaultEditableLyricsMatchKeyword(
                song.copy(
                    customName = "Edited",
                    customArtist = "Singer"
                )
            )
        )
        assertEquals("", defaultEditableLyricsMatchKeyword(song.copy(name = "", artist = "")))
        assertEquals("network",
            editableLyricMatchFailureDescription(IllegalStateException("network"))
        )
        assertEquals("IllegalStateException",
            editableLyricMatchFailureDescription(IllegalStateException(""))
        )
    }

    @Test
    fun `result merge preserves a successful same query source on empty retry`() {
        val source = EditableLyricMatchSource.KUGOU
        val old = match("old")
        val next = match("next")
        assertEquals(
            listOf(old),
            mergeEditableLyricMatchResults(
                mapOf(source to listOf(old)),
                emptyList(),
                setOf(source),
                true
            )[source]
        )
        assertEquals(
            listOf(next),
            mergeEditableLyricMatchResults(
                mapOf(source to listOf(old)),
                listOf(next),
                setOf(source),
                true
            )[source]
        )
        assertEquals(
            emptyList<RankedEditableLyricMatch>(),
            mergeEditableLyricMatchResults(emptyMap(), emptyList(), setOf(source), true)[source]
        )
        assertEquals(
            emptyList<RankedEditableLyricMatch>(),
            mergeEditableLyricMatchResults(
                mapOf(source to listOf(old)),
                emptyList(),
                setOf(source),
                false
            )[source]
        )
    }

    private fun kotlinx.coroutines.test.TestScope.owner(
        matcher: suspend (EditableLyricMatchRequest) -> List<RankedEditableLyricMatch>
    ) = NowPlayingLyricsEditorOwner(
        song = song,
        initialLyrics = "original",
        initialTranslatedLyrics = "translation",
        initialRomanizedLyrics = "romanized",
        scope = this,
        matchLyrics = matcher,
        matchDispatcher = StandardTestDispatcher(testScheduler)
    )

    private fun match(value: String, translation: String? = null) = RankedEditableLyricMatch(
        candidate = EditableLyricMatchCandidate(
            id = value,
            source = EditableLyricMatchSource.KUGOU,
            title = value,
            artist = "Artist",
            lyrics = value,
            translatedLyrics = translation
        ),
        score = 80,
        durationDeltaMs = null
    )
}
