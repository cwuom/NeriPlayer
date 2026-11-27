package moe.ouom.neriplayer.ui.screen

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.component.playlist.NeteaseRemotePlaylistPickerDialog
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.NeteaseCollectionFeedbackHost
import moe.ouom.neriplayer.ui.viewmodel.nowplaying.NeteaseCollectionFeedback
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.NeteasePlaylistCollectionAction
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.nowplaying.NowPlayingNeteasePlaylistOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.common.R as CoreCommonR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w412dp-h900dp")
class NowPlayingNeteasePlaylistComposeTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val song = SongItem(123L, "Song", "Artist", "Album", 1L, 120_000L, null, channelId = "netease")
    private val playlist = NeteaseRemotePlaylist(42L, "My playlist", 2)

    @Test
    fun `material menu selects a playlist and submits once`() = assertCollection(UiStyle.Material)

    @Test
    fun `clean menu selects a playlist and submits once`() = assertCollection(UiStyle.Clean)

    private fun assertCollection(style: UiStyle) {
        val completion = CompletableDeferred<NeteaseLikeSyncResult>()
        var menuOpen by mutableStateOf(true)
        val feedback = NeteaseCollectionFeedback()
        var screenMounted by mutableStateOf(true)
        val submissions = mutableListOf<Pair<Long, SongItem>>()
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                NowPlayingNeteasePlaylistOwner(scope, { true }, { listOf(playlist) }, { id, track ->
                    submissions += id to track
                    completion.await()
                }, feedback::show)
            }
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false, seedColorHex = "885522",
                uiStyle = style, animateColors = false) {
                Surface {
                    if (screenMounted) {
                        Box(Modifier.fillMaxSize()) {
                            if (menuOpen) {
                                NeteasePlaylistCollectionAction(owner, song,
                                    PlaybackAudioInfo(PlaybackAudioSource.NETEASE), false, false) { menuOpen = false }
                            }
                            NeteaseCollectionFeedbackHost(feedback, remember { SnackbarHostState() }, menuOpen)
                        }
                    }
                }
            }
        }
        val label = context.getString(CoreCommonR.string.nowplaying_netease_playlist_collect)
        composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(playlist.name).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(playlist.name).assertDoesNotExist()
        composeRule.onNodeWithText(label).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.nowplaying_netease_playlist_adding, playlist.name)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(playlist.id to song), submissions)
            screenMounted = false
        }
        composeRule.runOnIdle {
            completion.complete(NeteaseLikeSyncResult(1, 1, 0, 0, 1, 0))
        }
        composeRule.runOnIdle { screenMounted = true }
        val added = context.getString(CoreCommonR.string.nowplaying_netease_playlist_added, playlist.name)
        composeRule.onNodeWithText(added).assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(10_000)
        composeRule.onNodeWithText(added).assertDoesNotExist()
        composeRule.runOnIdle { screenMounted = false }
        composeRule.runOnIdle { screenMounted = true }
        composeRule.onNodeWithText(added).assertDoesNotExist()
    }

    @Test
    fun `disconnect after loading shows error inside the picker and permits retry`() {
        var offline by mutableStateOf(false)
        var submissions = 0
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                NowPlayingNeteasePlaylistOwner(scope, { true }, { listOf(playlist) }, { _, _ ->
                    submissions++
                    NeteaseLikeSyncResult(1, 1, 0, 0, 1, 0)
                }, {})
            }
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", animateColors = false) {
                NeteasePlaylistCollectionAction(owner, song, PlaybackAudioInfo(PlaybackAudioSource.NETEASE),
                    offline, false) {}
            }
        }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.nowplaying_netease_playlist_collect)).performClick()
        composeRule.runOnIdle { offline = true }
        composeRule.onNodeWithText(playlist.name).performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.nowplaying_netease_playlist_offline)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, submissions); offline = false }
        composeRule.onNodeWithText(playlist.name).performClick()
        composeRule.onNodeWithText(playlist.name).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, submissions) }
    }

    @Test
    fun `fallback removes the action and an open picker`() {
        var source by mutableStateOf(PlaybackAudioSource.NETEASE)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                NowPlayingNeteasePlaylistOwner(scope, { true }, { listOf(playlist) },
                    { _, _ -> error("must not submit") }, {})
            }
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", animateColors = false) {
                Surface {
                    NeteasePlaylistCollectionAction(owner, song, PlaybackAudioInfo(source), false, false) {}
                }
            }
        }
        val label = context.getString(CoreCommonR.string.nowplaying_netease_playlist_collect)
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(playlist.name).assertIsDisplayed()
        composeRule.runOnIdle { source = PlaybackAudioSource.BILIBILI }
        composeRule.onNodeWithText(label).assertDoesNotExist()
        composeRule.onNodeWithText(playlist.name).assertDoesNotExist()
        composeRule.runOnIdle { source = PlaybackAudioSource.NETEASE }
        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithText(playlist.name).assertDoesNotExist()
    }

    @Test
    fun `shared picker keeps loading rows disabled and preserves selection and cancel callbacks`() {
        var loading by mutableStateOf(true)
        val selections = mutableListOf<NeteaseRemotePlaylist>()
        var dismissals = 0
        composeRule.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", animateColors = false) {
                NeteaseRemotePlaylistPickerDialog(listOf(playlist), loading, null,
                    { selections += it }, { dismissals++ })
            }
        }
        composeRule.onNodeWithText(playlist.name).assertIsNotEnabled()
        composeRule.runOnIdle { loading = false }
        composeRule.onNodeWithText(playlist.name).performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_cancel)).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(playlist), selections)
            assertEquals(1, dismissals)
        }
    }
}
