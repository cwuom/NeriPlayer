package moe.ouom.neriplayer.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LoadedLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingFastLyricsResult
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsRefreshVersions
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsSnapshotCache
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricsLoadOwner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NowPlayingLyricsRefreshComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var song by mutableStateOf<SongItem?>(
        SongItem(41L, "Current", "Artist", "Bilibili", 1L, 60_000L, null)
    )
    private var mediaUrl by mutableStateOf("https://media/first")
    private var versions by mutableStateOf(NowPlayingLyricsRefreshVersions(0L, 0, 0L))
    private var visible by mutableStateOf(true)
    private val stages = ControlledStages()

    @Test
    fun `turning off cc removes the rendered original and translation`() {
        render()
        assertCaption("CC original", "CC translation")

        composeRule.runOnIdle {
            stages.background = { emptyLyrics() }
            versions = versions.copy(lyricsPreferenceRevision = 1L)
        }

        assertCaption("No lyrics", "none")
        composeRule.onNodeWithText("CC original").assertDoesNotExist()
        composeRule.onNodeWithText("Translation: CC translation").assertDoesNotExist()
    }

    @Test
    fun `reentering a pending preference refresh cannot retain old captions or publish a late result`() {
        render()
        assertCaption("CC original", "CC translation")
        val entered = CompletableDeferred<Unit>()
        val lateFast = CompletableDeferred<LoadedLyricsState>()
        try {
            composeRule.runOnIdle {
                stages.fast = { request ->
                    if (request.currentMediaUrl == "https://media/first") {
                        entered.complete(Unit)
                        withContext(NonCancellable) { lateFast.await() }
                    } else emptyLyrics()
                }
                stages.background = { emptyLyrics() }
                versions = versions.copy(lyricsPreferenceRevision = 1L)
            }
            assertCaption("CC original", "CC translation")
            composeRule.waitUntil(5_000L) { entered.isCompleted }

            composeRule.runOnIdle { mediaUrl = "https://media/reconnected" }

            assertCaption("No lyrics", "none")
            composeRule.runOnIdle { lateFast.complete(lyrics("late CC original", "late CC translation")) }
            assertCaption("No lyrics", "none")
            composeRule.onNodeWithText("late CC original").assertDoesNotExist()
        } finally {
            lateFast.complete(emptyLyrics())
        }
    }

    @Test
    fun `changing songs rejects the previous songs late captions`() {
        val entered = CompletableDeferred<Unit>()
        val lateBackground = CompletableDeferred<LoadedLyricsState>()
        stages.fast = { request ->
            if (request.song?.id == 41L) lyrics("CC original", "CC translation")
            else lyrics("New original", "New translation")
        }
        stages.background = { request ->
            if (request.song?.id == 41L) {
                entered.complete(Unit)
                withContext(NonCancellable) { lateBackground.await() }
            } else lyrics("New original", "New translation")
        }
        try {
            render()
            assertCaption("CC original", "CC translation")
            composeRule.waitUntil(5_000L) { entered.isCompleted }

            composeRule.runOnIdle { song = song?.copy(id = 42L, name = "Next") }

            assertCaption("New original", "New translation")
            composeRule.runOnIdle { lateBackground.complete(lyrics("late old original", "late old translation")) }
            assertCaption("New original", "New translation")
            composeRule.onNodeWithText("late old original").assertDoesNotExist()
        } finally {
            lateBackground.complete(emptyLyrics())
        }
    }

    @Test
    fun `reopening the player restores both completed caption tracks`() {
        render()
        assertCaption("CC original", "CC translation")
        composeRule.runOnIdle { visible = false }
        composeRule.onNodeWithText("Player closed").assertIsDisplayed()
        val pending = CompletableDeferred<LoadedLyricsState>()
        try {
            composeRule.runOnIdle {
                stages.background = { pending.await() }
                visible = true
            }

            assertCaption("CC original", "CC translation")
        } finally {
            pending.complete(emptyLyrics())
        }
    }

    private fun render() {
        composeRule.setContent {
            if (visible) {
                val owner = rememberNowPlayingLyricsLoadOwner(
                    request = NowPlayingLyricsLoadRequest(
                        application, song, mediaUrl, false, LyricSourcePreference.Automatic, null
                    ),
                    versions = versions,
                    stages = stages
                )
                Column {
                    Text(owner.state.lyrics.joinToString("\n") { it.text }.ifEmpty { "No lyrics" })
                    val translation = owner.state.translatedLyrics.joinToString("\n") { it.text }.ifEmpty { "none" }
                    Text("Translation: $translation")
                }
            } else {
                Text("Player closed")
            }
        }
    }

    private fun assertCaption(original: String, translation: String) {
        composeRule.onNodeWithText(original).assertIsDisplayed()
        composeRule.onNodeWithText("Translation: $translation").assertIsDisplayed()
    }

    private class ControlledStages : NowPlayingLyricsStages {
        override val snapshotCache = NowPlayingLyricsSnapshotCache()
        var fast: suspend (NowPlayingLyricsLoadRequest) -> LoadedLyricsState = { emptyLyrics() }
        var background: suspend (NowPlayingLyricsLoadRequest) -> LoadedLyricsState = {
            lyrics("CC original", "CC translation")
        }

        override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
            NowPlayingFastLyricsResult(fast(request), null, null, false, false)

        override suspend fun readBackground(
            request: NowPlayingLyricsLoadRequest,
            fast: NowPlayingFastLyricsResult
        ): LoadedLyricsState = background(request)
    }

    private companion object {
        fun emptyLyrics(): LoadedLyricsState = buildNowPlayingFastLyricsState(null, null, null)

        fun lyrics(original: String, translation: String): LoadedLyricsState = buildNowPlayingFastLyricsState(
            "[00:01.00]$original", "[00:01.00]$translation", null
        )
    }
}
