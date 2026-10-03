package moe.ouom.neriplayer.ui.screen.tab.library

import android.app.Application
import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.artist.CreatorFollowButton
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.feedback.AppFeedbackHostEffect
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarTestTag
import moe.ouom.neriplayer.ui.viewmodel.tab.FollowedArtistImportUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.FollowedArtistImportViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CreatorFavoritesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context by lazy {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
    }

    @Before
    fun requireUnlockedEmulator() {
        assumeComposeHostAvailable()
    }

    @Test
    fun allPlatformsOpenTheirOwnCreatorsAndRenderBothThemes() {
        val repository = FavoritePlaylistRepository.getInstance(context)
        val sources = listOf(
            FAVORITE_SOURCE_NETEASE_ARTIST,
            FAVORITE_SOURCE_BILI_ARTIST,
            FAVORITE_SOURCE_YOUTUBE_ARTIST
        )
        runBlocking {
            check(repository.awaitInitialized())
            sources.forEachIndexed { index, source ->
                repository.addFavorite(
                    id = TEST_CREATOR_ID, name = "Creator ${index + 1}",
                    coverUrl = null, trackCount = 0, source = source,
                    browseId = if (source == FAVORITE_SOURCE_YOUTUBE_ARTIST) "UCsynthetic-creator" else null,
                    subtitle = "Music & stories", songs = emptyList()
                )
            }
        }
        var opened = ""
        var dark by mutableStateOf(false)
        try {
            setChineseContent {
                NeriTheme(
                    followSystemDark = false, forceDark = dark,
                    dynamicColor = false, seedColorHex = "7D5260"
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(top = 28.dp)) {
                            Text(
                                stringResource(CoreCommonR.string.library_tab_favorite),
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                            FavoritePlaylistList(
                                listState = rememberLazyListState(),
                                onHotPlaylistClick = {}, onNeteasePlaylistClick = {},
                                onNeteaseAlbumClick = {},
                                onNeteaseArtistClick = { opened = "netease:${it.id}" },
                                onBiliPlaylistClick = {}, onYouTubeMusicPlaylistClick = {},
                                onBiliUploaderClick = { opened = "bili:${it.mid}" },
                                onYouTubeMusicCreatorClick = { opened = "youtube:${it.browseId}" },
                                offlineMode = false
                            )
                        }
                    }
                }
            }
            compose.onNodeWithText(context.getString(CoreCommonR.string.library_favorite_tab_artists)).performClick()
            compose.onNodeWithText("Creator 1").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("netease:$TEST_CREATOR_ID", opened) }
            compose.onAllNodesWithText(context.getString(CoreCommonR.string.library_artist_platform_bili))[0].performClick()
            compose.onNodeWithText("Creator 2").assertIsDisplayed().performClick()
            compose.onNodeWithText("Creator 1").assertDoesNotExist()
            compose.runOnIdle { assertEquals("bili:$TEST_CREATOR_ID", opened) }
            compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_import)).assertDoesNotExist()
            compose.onAllNodesWithText(context.getString(CoreCommonR.string.library_artist_platform_youtube))[0].performClick()
            compose.onNodeWithText("Creator 3").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("youtube:UCsynthetic-creator", opened) }
            capture("creator-follows-light.png")
            compose.runOnIdle { dark = true }
            compose.waitForIdle()
            capture("creator-follows-dark.png")
        } finally {
            runBlocking { sources.forEach { repository.removeFavorite(TEST_CREATOR_ID, it) } }
        }
    }

    @Test
    fun creatorFollowButtonFollowsAndUnfollowsBothPlatforms() {
        val repository = FavoritePlaylistRepository.getInstance(context)
        val sources = listOf(FAVORITE_SOURCE_BILI_ARTIST, FAVORITE_SOURCE_YOUTUBE_ARTIST)
        val id = TEST_CREATOR_ID + 1
        runBlocking {
            check(repository.awaitInitialized())
            sources.forEach { repository.removeFavorite(id, it) }
        }
        var source by mutableStateOf(sources.first())
        try {
            setChineseContent {
                NeriTheme(
                    followSystemDark = false, forceDark = false,
                    dynamicColor = false, seedColorHex = "7D5260"
                ) {
                    Surface {
                        CreatorFollowButton(FavoritePlaylist(
                            id = id, name = "Synthetic creator", coverUrl = null,
                            trackCount = 0, source = source,
                            browseId = if (source == FAVORITE_SOURCE_YOUTUBE_ARTIST) "UCsynthetic-button" else null,
                            songs = emptyList()
                        ))
                    }
                }
            }
            sources.forEach { platform ->
                compose.runOnIdle { source = platform }
                compose.onNodeWithText(context.getString(CoreCommonR.string.artist_follow)).performClick()
                compose.waitUntil(5_000) { repository.isFavorite(id, platform) }
                waitForEnabledFollowButton(context.getString(CoreCommonR.string.artist_followed))
                compose.onNodeWithText(context.getString(CoreCommonR.string.artist_followed))
                    .assertIsDisplayed().performClick()
                compose.waitUntil(5_000) { !repository.isFavorite(id, platform) }
                waitForEnabledFollowButton(context.getString(CoreCommonR.string.artist_follow))
                compose.onNodeWithText(context.getString(CoreCommonR.string.artist_follow)).assertIsDisplayed()
            }
        } finally {
            runBlocking { sources.forEach { repository.removeFavorite(id, it) } }
        }
    }

    @Test
    fun offlineAndLoadingPreventImportWhilePlatformSelectionRemainsAvailable() {
        var selected = FavoriteArtistPlatform.NETEASE
        var importCalls = 0
        setChineseContent {
            var offline by remember { mutableStateOf(true) }
            var platform by remember { mutableStateOf(FavoriteArtistPlatform.NETEASE) }
            MaterialTheme {
                Column {
                    FavoriteArtistPlatformHeader(
                        selected = platform, count = 2,
                        onSelect = { platform = it; selected = it; offline = false },
                        importState = if (offline) FollowedArtistImportUiState() else
                            FollowedArtistImportUiState(FAVORITE_SOURCE_YOUTUBE_ARTIST, loading = true),
                        onImport = { importCalls++ }, offlineMode = offline, editMode = false
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_import)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_platform_youtube)).performClick()
        compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_import_loading)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(FavoriteArtistPlatform.YOUTUBE, selected)
            assertEquals(0, importCalls)
        }
    }

    @Test
    fun remoteImportResultsAppearInSnackbarAndDoNotReplayOnPlatformChanges() {
        var resultCount = 2
        var fail = false
        val importViewModel = FollowedArtistImportViewModel(
            application = context.applicationContext as Application,
            loadArtists = {
                if (fail) throw IOException("synthetic failure")
                emptyList()
            },
            mergeArtists = { _, _, _ -> resultCount },
            currentAccount = { "synthetic account" },
            ioDispatcher = Dispatchers.Main.immediate
        )
        val snackbar = SnackbarHostState()
        try {
            setChineseContent {
                NeriTheme(
                    followSystemDark = false, forceDark = false,
                    dynamicColor = false, seedColorHex = "7D5260"
                ) {
                    AppFeedbackHostEffect(snackbar)
                    Box(Modifier.fillMaxSize()) {
                        FavoritePlaylistList(
                            listState = rememberLazyListState(),
                            onHotPlaylistClick = {}, onNeteasePlaylistClick = {},
                            onNeteaseAlbumClick = {}, onNeteaseArtistClick = {},
                            onBiliPlaylistClick = {}, onYouTubeMusicPlaylistClick = {},
                            onBiliUploaderClick = {}, onYouTubeMusicCreatorClick = {},
                            offlineMode = false, importViewModel = importViewModel
                        )
                        NeriSnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            compose.onNodeWithText(context.getString(CoreCommonR.string.library_favorite_tab_artists)).performClick()
            val results = listOf(
                context.resources.getQuantityString(CoreCommonR.plurals.library_artist_import_success, 2, 2),
                context.getString(CoreCommonR.string.library_artist_import_no_new),
                context.getString(CoreCommonR.string.library_artist_import_failed)
            )
            results.forEachIndexed { index, message ->
                compose.runOnIdle { resultCount = if (index == 0) 2 else 0; fail = index == 2 }
                compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_import)).performClick()
                compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.message == message }
                compose.onNodeWithTag(NeriSnackbarTestTag).assertIsDisplayed()
                compose.onNode(hasText(message) and hasAnyAncestor(hasTestTag(NeriSnackbarTestTag)))
                    .assertIsDisplayed()
                compose.runOnIdle {
                    assertNull(importViewModel.uiState.value.importedCount)
                    assertNull(importViewModel.uiState.value.error)
                    snackbar.currentSnackbarData?.dismiss()
                }
                compose.waitUntil(5_000) { snackbar.currentSnackbarData == null }
                compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_platform_bili)).performClick()
                compose.onNodeWithText(context.getString(CoreCommonR.string.library_tab_netease)).performClick()
                compose.onNodeWithTag(NeriSnackbarTestTag).assertDoesNotExist()
            }
        } finally {
            importViewModel.viewModelScope.cancel()
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun waitForEnabledFollowButton(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().any {
                !it.config.contains(SemanticsProperties.Disabled)
            }
        }
    }

    private fun setChineseContent(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration
            ) {
                content()
            }
        }
    }

    companion object {
        private const val TEST_CREATOR_ID = 9_876_543_210L
    }
}
