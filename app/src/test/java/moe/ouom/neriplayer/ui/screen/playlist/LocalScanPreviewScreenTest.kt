package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.activity.LocalizedAppContent
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import java.util.Locale
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.local.LocalAudioScanPhase
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocalScanPreviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `scan preview updates its built-in album label without replacing songs or selection`() {
        val language = mutableStateOf(LanguageManager.Language.CHINESE)
        val songs = listOf(song(1L, "Track").copy(
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            localFilePath = "/tmp/sample.mp3"
        ))
        val selected = songs.map { it.stableKey() }.toSet()
        composeRule.setContent {
            LocalizedAppContent(language.value) {
                MaterialTheme {
                    LocalScanPreviewScreen(
                        isScanning = false,
                        songs = songs,
                        query = "",
                        onQueryChange = {},
                        selectedKeys = selected,
                        onSelectedKeysChange = {},
                        snackbarHostState = remember { SnackbarHostState() },
                        onBack = {},
                        onImport = {}
                    )
                }
            }
        }
        fun expectAlbum(tag: String) {
            val resources = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            ).resources
            val subtitle = "${resources.getString(CoreCommonR.string.local_files)} · sample.mp3"
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(subtitle).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(resources.getString(CoreCommonR.string.download_scan_add_selected, 1)).assertIsEnabled()
        }
        expectAlbum("zh")
        composeRule.runOnIdle { language.value = LanguageManager.Language.ENGLISH }
        expectAlbum("en")
    }

    @Test
    fun `scan preview selects listed songs and offers only the available filters`() {
        val songs = listOf(song(1L, "Alpha"), song(2L, "Beta"))
        var selectedKeys by mutableStateOf(emptySet<String>())
        var optionalFiltersAvailable by mutableStateOf(true)
        val metadataChanges = mutableListOf<Boolean>()
        val existingChanges = mutableListOf<Boolean>()
        val duplicateChanges = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                LocalScanPreviewScreen(
                    isScanning = false,
                    songs = songs,
                    query = "",
                    onQueryChange = {},
                    onMetadataOnlyChange = { metadataChanges += it },
                    onHideExistingLocalPlaylistSongsChange = if (optionalFiltersAvailable) {
                        { existingChanges += it }
                    } else {
                        null
                    },
                    onHideDuplicateMetadataSongsChange = if (optionalFiltersAvailable) {
                        { duplicateChanges += it }
                    } else {
                        null
                    },
                    selectedKeys = selectedKeys,
                    onSelectedKeysChange = { selectedKeys = it },
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onImport = {}
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Beta").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.local_playlist_scan_preview_title)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.download_scan_add_selected, 0)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_select_all)).performClick()
        assertEquals(songs.map { it.stableKey() }.toSet(), selectedKeys)
        composeRule.onNodeWithText(string(CoreCommonR.string.download_scan_add_selected, 2)).assertIsEnabled()

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.common_more_options)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.local_playlist_scan_filter_duplicates)).performClick()
        assertEquals(listOf(true), duplicateChanges)

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.common_more_options)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.local_playlist_scan_filter_existing)).performClick()
        assertEquals(listOf(true), existingChanges)

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.common_more_options)).performClick()
        optionalFiltersAvailable = false
        composeRule.onNodeWithText(string(CoreCommonR.string.local_playlist_scan_filter_existing)).assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.local_playlist_scan_filter_duplicates)).assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.local_playlist_scan_filter_metadata)).performClick()
        assertEquals(listOf(true), metadataChanges)
    }

    @Test
    fun `scanning without songs shows progress and keeps selection actions disabled`() {
        composeRule.setContent {
            MaterialTheme {
                LocalScanPreviewScreen(
                    isScanning = true,
                    scanProgress = LocalAudioScanProgress(
                        phase = LocalAudioScanPhase.COMPLETED,
                        discoveredSongs = 3
                    ),
                    songs = emptyList(),
                    query = "",
                    onQueryChange = {},
                    selectedKeys = setOf("selected"),
                    onSelectedKeysChange = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onImport = {},
                    onSecondaryAction = {},
                    secondaryActionLabel = "Move selected"
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.download_scanning)).assertExists()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                AppR.plurals.local_playlist_scan_progress_completed,
                3,
                3
            )
        ).assertExists()
        composeRule.onNodeWithText("Move selected").assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.download_scan_add_selected, 1)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.common_more_options)).assertDoesNotExist()
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun song(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artist = "",
        album = "",
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null
    )
}
