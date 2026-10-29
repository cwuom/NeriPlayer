package moe.ouom.neriplayer.ui.screen

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NowPlayingLyricOffsetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val cloud = MutableStateFlow(100L)
    private val qq = MutableStateFlow(200L)
    private val kugou = MutableStateFlow(300L)
    private val lrclib = MutableStateFlow(400L)
    private val amll = MutableStateFlow(500L)
    private val bili = MutableStateFlow(600L)
    private val repository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.cloudMusicLyricDefaultOffsetMsFlow).thenReturn(cloud)
        Mockito.`when`(it.qqMusicLyricDefaultOffsetMsFlow).thenReturn(qq)
        Mockito.`when`(it.kugouLyricDefaultOffsetMsFlow).thenReturn(kugou)
        Mockito.`when`(it.lrclibLyricDefaultOffsetMsFlow).thenReturn(lrclib)
        Mockito.`when`(it.amllTtmlLyricDefaultOffsetMsFlow).thenReturn(amll)
        Mockito.`when`(it.biliCcSubtitleLyricDefaultOffsetMsFlow).thenReturn(bili)
    }
    private var song by mutableStateOf(
        SongItem(1L, "Song", "Artist", "Album", 1L, 60_000L, null, userLyricOffsetMs = 50L)
    )
    private var preferredSource by mutableStateOf<LyricSourcePreference?>(null)
    private var isBiliSubtitle by mutableStateOf(false)

    @Test
    fun `rendered offset follows live preferences for every lyric source`() {
        render()
        assertOffset(150L)
        composeRule.runOnIdle { cloud.value = 150L }
        assertOffset(200L)

        val sources = listOf(
            Triple(LyricSourcePreference.QqMusic, qq, 200L),
            Triple(LyricSourcePreference.Kugou, kugou, 300L),
            Triple(LyricSourcePreference.LrcLib, lrclib, 400L),
            Triple(LyricSourcePreference.AmllTtml, amll, 500L),
            Triple(LyricSourcePreference.CloudMusic, cloud, 150L)
        )
        for ((source, preference, initialOffset) in sources) {
            composeRule.runOnIdle { preferredSource = source }
            assertOffset(initialOffset + 50L)
            composeRule.runOnIdle { preference.value = initialOffset + 100L }
            assertOffset(initialOffset + 150L)
        }
    }

    @Test
    fun `subtitle origin and song edits update offset without replacing the composition`() {
        render()
        assertOffset(150L)
        composeRule.runOnIdle { isBiliSubtitle = true }
        assertOffset(650L)
        composeRule.runOnIdle { bili.value = -200L }
        assertOffset(-150L)
        composeRule.runOnIdle { song = song.copy(userLyricOffsetMs = 150L) }
        assertOffset(-50L)

        composeRule.runOnIdle { preferredSource = LyricSourcePreference.Kugou }
        assertOffset(450L)
        composeRule.runOnIdle {
            preferredSource = null
            isBiliSubtitle = false
            song = song.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)
        }
        assertOffset(350L)
    }

    private fun render() {
        composeRule.setContent {
            val offset = rememberNowPlayingLyricOffset(
                repository = repository,
                song = song,
                preferredSource = preferredSource,
                isBiliCcSubtitleLyrics = isBiliSubtitle
            )
            Text("$offset ms")
        }
    }

    private fun assertOffset(expected: Long) {
        composeRule.onNodeWithText("$expected ms").assertIsDisplayed()
    }
}
