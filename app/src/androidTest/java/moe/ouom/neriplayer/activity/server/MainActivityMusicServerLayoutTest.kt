package moe.ouom.neriplayer.activity.server

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.car.CarMediaBrowserService
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import moe.ouom.neriplayer.ui.screen.server.captureMusicServerBitmap
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsPageRowTestTag
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityMusicServerLayoutTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val localized get() = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))

    @Test fun cleanBrowserUsesRealLibraryInsets() = browserInsets(UiStyle.Clean)
    @Test fun materialBrowserUsesRealLibraryInsets() = browserInsets(UiStyle.Material)
    @Test fun cleanSettingsCardsMatchExistingAccountInsets() = settingsInsets(UiStyle.Clean)
    @Test fun materialSettingsCardsMatchExistingAccountInsets() = settingsInsets(UiStyle.Material)
    @Test fun cleanAlbumUsesDetailLayoutAndRestoresBrowser() = albumNavigation(UiStyle.Clean)
    @Test fun materialAlbumUsesDetailLayoutAndRestoresBrowser() = albumNavigation(UiStyle.Material)
    @Test fun cleanDarkBrowserUsesRealLibraryInsets() = browserInsets(UiStyle.Clean, dark = true)
    @Test fun materialDarkBrowserUsesRealLibraryInsets() = browserInsets(UiStyle.Material, dark = true)
    @Test fun cleanDarkSettingsCardsMatchExistingAccountInsets() = settingsInsets(UiStyle.Clean, dark = true)
    @Test fun materialDarkSettingsCardsMatchExistingAccountInsets() = settingsInsets(UiStyle.Material, dark = true)
    @Test fun cleanDarkAlbumUsesDetailLayoutAndRestoresBrowser() = albumNavigation(UiStyle.Clean, dark = true)
    @Test fun materialDarkAlbumUsesDetailLayoutAndRestoresBrowser() = albumNavigation(UiStyle.Material, dark = true)
    @Test fun albumMoreMenuCompletesARealWifiDownload() = downloadFromAlbumMenu()
    @Test fun cleanArtistNavigationRestoresArtistAndRootState() = artistNavigation(UiStyle.Clean)
    @Test fun materialArtistNavigationRestoresArtistAndRootState() = artistNavigation(UiStyle.Material)
    @Test fun cleanDarkArtistNavigationRestoresArtistAndRootState() = artistNavigation(UiStyle.Clean, dark = true)
    @Test fun materialDarkArtistNavigationRestoresArtistAndRootState() = artistNavigation(UiStyle.Material, dark = true)
    @Test fun cleanExploreDialogRestoresArtistAndCloses() = exploreArtistNavigation(UiStyle.Clean)
    @Test fun materialExploreDialogRestoresArtistAndCloses() = exploreArtistNavigation(UiStyle.Material)
    @Test fun cleanDarkExploreDialogRestoresArtistAndCloses() = exploreArtistNavigation(UiStyle.Clean, dark = true)
    @Test fun materialDarkExploreDialogRestoresArtistAndCloses() = exploreArtistNavigation(UiStyle.Material, dark = true)
    @Test fun cleanRootSongsSupportSequentialAndShufflePlayback() = rootSongPlayback(UiStyle.Clean)
    @Test fun materialRootSongsSupportSequentialAndShufflePlayback() = rootSongPlayback(UiStyle.Material)
    @Test fun cleanRootSelectionUsesDedicatedListAndRestoresBrowser() = rootSongSelection(UiStyle.Clean)
    @Test fun materialRootSelectionUsesDedicatedListAndRestoresBrowser() = rootSongSelection(UiStyle.Material)
    @Test fun cleanDarkRootSelectionUsesDedicatedListAndRestoresBrowser() = rootSongSelection(UiStyle.Clean, dark = true)
    @Test fun materialDarkRootSelectionUsesDedicatedListAndRestoresBrowser() = rootSongSelection(UiStyle.Material, dark = true)
    @Test fun cleanRootSelectionKeepsPaddingAnchor() = rootSongSelection(UiStyle.Clean, paddingEdge = true)
    @Test fun materialRootSelectionKeepsPaddingAnchor() = rootSongSelection(UiStyle.Material, paddingEdge = true)

    private fun rootSongSelection(style: UiStyle, dark: Boolean = false, paddingEdge: Boolean = false) =
        withRealActivity(style, if (paddingEdge) "selection-padding" else "selection", dark = dark) { session ->
            val songs = runBlocking { AppContainer.subsonicRepository.catalog(session.profile.id, ServerLibraryCategory.SONGS).songs }
            assertEquals("selection fixture must expose all 65 songs", 65, songs.size)
            openServerTab(session)
            clickableText(text(CoreCommonR.string.server_tab_songs)).performClick()
            await { visibleOrNull(rootSongMatcher(songs.first())) != null }
            visibleNode(hasSetTextAction()).performTextReplacement(Query)
            visibleNode(hasSetTextAction()).performImeAction()
            await { visibleOrNull(hasText(text(CoreCommonR.string.server_search_results, Query))) != null }
            val browseList = listContaining(visibleNode(hasSetTextAction()).fetchSemanticsNode())
            val originalQueue = PlayerManager.currentQueueFlow.value.map(SongItem::stableKey)
            data class BrowseAnchor(val position: Float, val song: SongItem, val rowOffset: Float)

            fun enterSelection(song: SongItem): BrowseAnchor {
                browseList.performScrollToNode(rootSongMatcher(song))
                if (paddingEdge && song == songs[18]) {
                    val first = firstVisibleCatalogSong(songs)
                    val row = rootSongRow(first).fetchSemanticsNode().boundsInRoot
                    val viewport = browseList.fetchSemanticsNode().boundsInRoot
                    browseList.performSemanticsAction(SemanticsActions.ScrollBy) {
                        it(0f, row.bottom - viewport.top - dp(3f))
                    }
                }
                val target = unobscuredAppContentTarget { visibleNode(rootSongMatcher(song)) }
                val before = scrollPosition(browseList)
                val anchor = firstVisibleCatalogSong(songs)
                val rowOffset = rootSongRow(anchor).fetchSemanticsNode().boundsInRoot.top -
                    browseList.fetchSemanticsNode().boundsInRoot.top
                capture(session, "before-select-${songs.indexOf(song)}")
                target.performTouchInput { longClick() }
                await { visibleOrNull(hasText(text(CoreCommonR.string.server_play_selected))) != null }
                awaitSelectionCount(1)
                assertSelectionChrome(session)
                capture(session, "entered-select-${songs.indexOf(song)}")
                val observed = firstVisibleCatalogSong(songs)
                assertEquals("selection starts at the browser's first visible song: ${anchor.name} -> ${observed.name}",
                    anchor.stableKey(), observed.stableKey())
                return BrowseAnchor(before, anchor, rowOffset)
            }

            fun assertRestored(before: BrowseAnchor, stage: String) {
                await { visibleOrNull(hasText(text(CoreCommonR.string.server_play_selected))) == null }
                val restoredSong = rootSongRow(before.song).fetchSemanticsNode()
                val restored = listContaining(restoredSong)
                assertEquals("leaving selection restores the untouched browser position", before.position, scrollPosition(restored), 2f)
                assertEquals("leaving selection restores the same first visible song", before.song.stableKey(),
                    firstVisibleCatalogSong(songs).stableKey())
                assertEquals("leaving selection restores the song's pixel offset", before.rowOffset,
                    clickableBounds(restoredSong).top - restored.fetchSemanticsNode().boundsInRoot.top, 2f)
                assertTrue("selection checkboxes leave with selection mode", composeRule.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState), useUnmergedTree = true
                ).fetchSemanticsNodes().isEmpty())
                capture(session, stage)
                restored.performScrollToNode(hasSetTextAction())
                visibleNode(hasSetTextAction()).assertTextContains(Query)
                visibleText(text(CoreCommonR.string.server_search_results, Query)).assertIsDisplayed()
                restored.performScrollToNode(hasText(text(CoreCommonR.string.server_tab_songs)))
                clickableText(text(CoreCommonR.string.server_tab_songs)).assertIsSelected()
                assertAppChrome()
            }

            val completedAnchor = enterSelection(songs[18])
            rowCheckbox(songs[18]).assertIsOn()
            capture(session, "selected-scrolled")
            val selectionList = listContaining(rootSongRow(completedAnchor.song).fetchSemanticsNode())
            selectionList.performScrollToNode(rootSongMatcher(songs[42]))
            val distantSong = unobscuredAppContentTarget { visibleNode(rootSongMatcher(songs[42])) }
            distantSong.assertIsDisplayed()
            awaitSelectionCount(1)
            rowCheckbox(songs[42]).assertIsOff()
            assertSelectionChrome(session)
            visibleNode(hasContentDescription(text(CoreCommonR.string.action_select_all))).performClick()
            awaitSelectionCount(songs.size)
            rowCheckbox(songs[42]).assertIsOn()
            visibleNode(hasContentDescription(text(CoreCommonR.string.action_deselect_all))).performClick()
            awaitSelectionCount(0)
            rowCheckbox(songs[42]).assertIsOff()
            clickableText(text(CoreCommonR.string.server_play_selected)).assertIsNotEnabled()
            rowCheckbox(songs[42]).performClick()
            awaitSelectionCount(1)
            rowCheckbox(songs[42]).assertIsOn()
            capture(session, "selected-after-scroll")
            clickableText(text(CoreCommonR.string.action_done)).performClick()
            assertRestored(completedAnchor, "done-restored")

            val backAnchor = enterSelection(songs.first())
            capture(session, "selected-near-browser-controls")
            Espresso.pressBack()
            assertRestored(backAnchor, "back-restored")
            assertEquals("selection gestures must not change the playback queue", originalQueue,
                PlayerManager.currentQueueFlow.value.map(SongItem::stableKey))
        }

    private fun rootSongMatcher(song: SongItem) =
        hasText(song.name) and hasAnyAncestor(hasScrollToIndexAction())

    private fun rootSongRow(song: SongItem): SemanticsNodeInteraction {
        val rows = composeRule.onAllNodes(rootSongMatcher(song), useUnmergedTree = true).fetchSemanticsNodes()
            .map { ancestorWith(it, SemanticsActions.OnClick) }.distinctBy(SemanticsNode::id)
        return node(rows.single { runCatching { node(it.id).isDisplayed() }.getOrDefault(false) }.id)
    }

    private fun firstVisibleCatalogSong(songs: List<SongItem>): SongItem {
        val byName = songs.associateBy(SongItem::name)
        val titles = composeRule.onAllNodes(SemanticsMatcher("catalog song title") { candidate ->
            candidate.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text in byName }
        } and hasAnyAncestor(hasScrollToIndexAction()), useUnmergedTree = true).fetchSemanticsNodes()
        // 上一行的空白内边距可能仍可点击，按可见标题判断首个歌曲
        val first = titles.filter { title ->
            title.boundsInRoot.width > 0f && title.boundsInRoot.height > 0f &&
                runCatching { node(title.id).isDisplayed() }.getOrDefault(false)
        }.minByOrNull { it.boundsInRoot.top } ?: throw AssertionError("Expected a visible catalog song title")
        return checkNotNull(byName[first.config[SemanticsProperties.Text].first().text])
    }

    private fun rowCheckbox(song: SongItem): SemanticsNodeInteraction {
        val row = rootSongRow(song).fetchSemanticsNode()
        fun checkboxes(start: SemanticsNode): List<SemanticsNode> =
            (if (start.config.getOrNull(SemanticsProperties.ToggleableState) != null) listOf(start) else emptyList()) +
                start.children.flatMap(::checkboxes)
        return node(checkboxes(row).single().id)
    }

    private fun awaitSelectionCount(count: Int) = await {
        visibleOrNull(hasText(localized.resources.getQuantityString(AppR.plurals.common_selected_count, count, count))) != null
    }

    private fun assertSelectionChrome(session: Session) {
        assertTrue("selection removes the browser's search field", composeRule.onAllNodes(
            hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        for (label in listOf(session.profile.label, text(CoreCommonR.string.server_tab_albums),
            text(CoreCommonR.string.server_tab_songs), text(CoreCommonR.string.server_tab_artists))) {
            assertTrue("selection removes browser control: $label", composeRule.onAllNodes(
                hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        val play = clickableText(text(CoreCommonR.string.server_play_selected)).fetchSemanticsNode().boundsInRoot
        val done = clickableText(text(CoreCommonR.string.action_done)).fetchSemanticsNode().boundsInRoot
        val checkbox = visibleNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNode()
        val list = listContaining(checkbox).fetchSemanticsNode().boundsInRoot
        assertTrue("selection count and exit row stay above its playback action", done.bottom <= play.top + 2f)
        assertTrue("selection playback action leaves a clear gap before the songs", play.bottom + dp(8f) <= list.top + 2f)
        assertTrue("selection playback stays inside the list's horizontal page bounds", play.left >= list.left - 2f &&
            play.right <= list.right + 2f)
        assertAppChrome()
    }

    private fun rootSongPlayback(style: UiStyle) = withRealActivity(style, "shuffle") { session ->
        val songs = runBlocking { AppContainer.subsonicRepository.catalog(session.profile.id, ServerLibraryCategory.SONGS).songs }
        assertEquals("playback fixture must expose all 65 songs", 65, songs.size)
        assertEquals("playback fixture needs 65 distinct source identities", 65, songs.map(SongItem::stableKey).toSet().size)
        openServerTab(session)
        clickableText(text(CoreCommonR.string.server_tab_songs)).performClick()
        fun awaitSongCount(count: Int) = await {
            visibleOrNull(hasText(localized.resources.getQuantityString(AppR.plurals.explore_song_count, count, count))) != null
        }
        awaitSongCount(65)
        playRootSongs(session, songs, shuffled = false)
        capture(session, "ordered")
        playRootSongs(session, songs, shuffled = true)
        capture(session, "shuffled")
        playRootSongs(session, songs, shuffled = false)
        capture(session, "ordered-again")

        val subsetQuery = songs.first().name
        val subset = songs.filter { song ->
            listOf(song.name, song.artist, song.album).any { it.contains(subsetQuery, ignoreCase = true) }
        }
        assertTrue("a complete fixture song name must select one or two tracks", subset.size in 1..2)
        fun search(query: String, count: Int) {
            val list = listContaining(visibleText(text(CoreCommonR.string.player_play_all)).fetchSemanticsNode())
            list.performScrollToNode(hasSetTextAction())
            visibleNode(hasSetTextAction()).performTextReplacement(query)
            visibleNode(hasSetTextAction()).performImeAction()
            await { visibleOrNull(hasText(text(CoreCommonR.string.server_search_results, query))) != null }
            awaitSongCount(count)
        }
        search(subsetQuery, subset.size)
        playRootSongs(session, subset, shuffled = true)
        capture(session, "filtered-shuffle")

        val queueBeforeEmptySearch = PlayerManager.currentQueueFlow.value.map(SongItem::stableKey)
        search("PR511 no matching shuffle fixture song", 0)
        for (label in listOf(CoreCommonR.string.player_play_all, CoreCommonR.string.player_shuffle_play)) {
            unobscuredAppContentTarget { clickableText(text(label)) }.assertIsDisplayed().assertIsNotEnabled()
        }
        assertEquals("an empty result must leave the playback queue intact", queueBeforeEmptySearch,
            PlayerManager.currentQueueFlow.value.map(SongItem::stableKey))
        capture(session, "empty-disabled")
    }

    private fun playRootSongs(session: Session, songs: List<SongItem>, shuffled: Boolean) {
        val expectedKeys = songs.map(SongItem::stableKey).toSet()
        val label = if (shuffled) CoreCommonR.string.player_shuffle_play else CoreCommonR.string.player_play_all
        val button = unobscuredAppContentTarget { clickableText(text(label)) }.assertIsDisplayed().assertIsEnabled()
        try {
            button.performTouchInput { click() }
            await {
                val queueKeys = PlayerManager.currentQueueFlow.value.map(SongItem::stableKey)
                val current = PlayerManager.currentSongFlow.value?.stableKey()
                queueKeys.size == songs.size && queueKeys.toSet() == expectedKeys &&
                    PlayerManager.shuffleModeFlow.value == shuffled &&
                    if (shuffled) current in expectedKeys else current == songs.first().stableKey()
            }
            assertEquals("playback must contain exactly the current server results", expectedKeys,
                PlayerManager.currentQueueFlow.value.map(SongItem::stableKey).toSet())
            assertEquals("the clicked playback action sets the requested mode", shuffled, PlayerManager.shuffleModeFlow.value)
            if (!shuffled) assertEquals("ordered playback starts at the first result", songs.first().stableKey(),
                PlayerManager.currentSongFlow.value?.stableKey())
        } finally {
            val actualKeys = PlayerManager.currentQueueFlow.value.map(SongItem::stableKey)
            // 只登记本次点击实际接管且来自已取得目录的曲目，失败时仍不接管其它队列
            if (actualKeys.isNotEmpty() && actualKeys.all { it in expectedKeys }) session.ownedPlaybackKeys += actualKeys
        }
    }

    private fun browserInsets(style: UiStyle, dark: Boolean = false) = withRealActivity(style, "browse", dark = dark) { session ->
        openServerTab(session)
        val input = visibleNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val expectedInset = dp(if (style == UiStyle.Clean) 20f else 16f)
        capture(session, "browse")
        assertTrue("server search must keep the theme's page start inset: $input in $root",
            input.left - root.left >= expectedInset - 2f)
        assertTrue("server search must keep the theme's page end inset: $input in $root",
            root.right - input.right >= expectedInset - 2f)
        val row = clickableBounds(visibleText(session.albums.first().name).fetchSemanticsNode())
        assertTrue("album rows must share the search start inset", row.left >= input.left - 2f)
        assertTrue("album rows must share the search end inset", row.right <= input.right + 2f)
        assertAppChrome()
        val songs = runBlocking { AppContainer.subsonicRepository.catalog(session.profile.id, ServerLibraryCategory.SONGS).songs }
        assertEquals("real song catalog is complete", 65, songs.size)
        clickableText(text(CoreCommonR.string.server_tab_songs)).performClick()
        val songMatcher = hasText(songs.first().name) and hasAnyAncestor(hasScrollToIndexAction())
        await { visibleOrNull(songMatcher) != null }
        val list = listContaining(visibleNode(hasSetTextAction()).fetchSemanticsNode())
        list.performScrollToNode(songMatcher)
        val songTitle = unobscuredAppContentTarget { visibleNode(songMatcher) }.fetchSemanticsNode()
        val songRow = clickableBounds(songTitle)
        assertTrue("song title keeps at least half the actual library row width",
            songTitle.boundsInRoot.width >= songRow.width * 0.5f)
        assertTrue("server songs must not add a second page inset",
            songTitle.boundsInRoot.left - songRow.left <= dp(92f))
        capture(session, "songs")
        assertAppChrome()
    }

    private fun settingsInsets(style: UiStyle, dark: Boolean = false) = withRealActivity(style, "accounts", settingsStart = true, dark = dark) { session ->
        composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.Accounts))
            .performScrollTo().performClick()
        val existingCard = composeRule.onNodeWithTag("settingsAccountCard:netease")
        await { existingCard.isDisplayed() }
        val existingBounds = existingCard.fetchSemanticsNode().boundsInRoot
        val existingButton = composeRule.onNodeWithTag("settingsAccountLogin:netease")
            .fetchSemanticsNode().boundsInRoot
        val referenceInset = existingBounds.right - existingButton.right
        val card = composeRule.onNodeWithTag("settingsMusicServerCard")
        card.performScrollTo()
        await { card.isDisplayed() }
        val bounds = card.fetchSemanticsNode().boundsInRoot
        val manage = clickableBounds(visibleText(text(CoreCommonR.string.server_manage_accounts)).fetchSemanticsNode())
        assertEquals("server settings entry must use the existing account card's action inset",
            referenceInset, bounds.right - manage.right, 2f)
        val manageButton = unobscuredAppContentTarget { clickableText(text(CoreCommonR.string.server_manage_accounts)) }
        capture(session, "accounts")
        manageButton.performTouchInput { click() }
        val profileCard = composeRule.onNodeWithTag("serverAccount:${session.profile.id}")
        await { composeRule.onNodeWithTag("serverAccountIntro").isDisplayed() || profileCard.isDisplayed() }
        profileCard.performScrollTo()
        await { profileCard.isDisplayed() }
        val accountBounds = profileCard.fetchSemanticsNode().boundsInRoot
        val label = visibleText(session.profile.label).fetchSemanticsNode().boundsInRoot
        capture(session, "server-settings")
        assertTrue("saved server profile text needs the shared settings card's inner inset",
            label.left - accountBounds.left >= referenceInset - 2f)
        assertAppChrome()
    }

    private fun albumNavigation(style: UiStyle, dark: Boolean = false) = withRealActivity(style, "album", dark = dark) { session ->
        val album = session.albums[8]
        val songs = runBlocking { AppContainer.subsonicRepository.albumSongs(session.profile.id, album.id) }
        assertTrue("comparison needs real fixture album tracks", songs.isNotEmpty())
        val playlists = LocalPlaylistRepository.getInstance(context)
        val local = runBlocking { playlists.createPlaylistWithSongs("PR511 layout comparison", songs) }
        session.localPlaylistId = local.id
        await { visibleOrNull(hasText(local.name)) != null }
        clickableText(local.name).performClick()
        await { visibleCoverOrNull(local.name) != null }
        val localHero = checkNotNull(visibleCoverOrNull(local.name)).boundsInRoot
        listContaining(checkNotNull(visibleCoverOrNull(local.name))).performScrollToNode(hasText(songs.first().name))
        val localRow = clickableBounds(visibleText(songs.first().name).fetchSemanticsNode())
        val localTitle = visibleText(songs.first().name).fetchSemanticsNode().boundsInRoot
        capture(session, "local-comparison")
        Espresso.pressBack()
        openServerTab(session)
        visibleNode(hasSetTextAction()).performTextReplacement(Query)
        visibleNode(hasSetTextAction()).performImeAction()
        await { visibleOrNull(hasText(text(CoreCommonR.string.server_search_results, Query))) != null }
        val list = listContaining(visibleNode(hasSetTextAction()).fetchSemanticsNode())
        list.performScrollToNode(hasText(album.name))
        val before = scrollPosition(list)
        clickableText(album.name).performClick()
        awaitAlbumOpened(album)
        capture(session, "album")
        assertTrue("album detail must replace the library heading and platform tabs",
            visibleOrNull(libraryHeading()) == null &&
                visibleOrNull(hasText(text(CoreCommonR.string.library_tab_local))) == null)
        await { visibleCoverOrNull(album.name) != null }
        val serverHero = checkNotNull(visibleCoverOrNull(album.name)) { "album detail needs its real hero artwork" }.boundsInRoot
        assertEquals("server album hero width must match the local playlist hero", localHero.width, serverHero.width, 2f)
        assertEquals("server album hero height must match the local playlist hero", localHero.height, serverHero.height, 2f)
        assertEquals("server album hero start must match the local playlist hero", localHero.left, serverHero.left, 2f)
        listContaining(checkNotNull(visibleCoverOrNull(album.name))).performScrollToNode(hasText(songs.first().name))
        val serverRow = clickableBounds(visibleText(songs.first().name).fetchSemanticsNode())
        val serverTitle = visibleText(songs.first().name).fetchSemanticsNode().boundsInRoot
        assertEquals("server song row height must match local playlist rows", localRow.height, serverRow.height, 2f)
        assertEquals("server song title inset must match local playlist rows",
            localTitle.left - localRow.left, serverTitle.left - serverRow.left, 2f)
        assertAppChrome()
        Espresso.pressBack()
        await { visibleOrNull(hasText(album.name)) != null }
        val restored = listContaining(visibleText(album.name).fetchSemanticsNode())
        assertEquals("return must preserve server browser scroll", before, scrollPosition(restored), 2f)
        restored.performScrollToNode(hasSetTextAction())
        visibleNode(hasSetTextAction()).assertTextContains(Query)
        clickableText(text(CoreCommonR.string.server_tab_albums)).assertIsSelected()
        capture(session, "returned-browser")
    }

    private fun artistNavigation(style: UiStyle, dark: Boolean = false) = withRealActivity(style, "artist", dark = dark) { session ->
        val repository = AppContainer.subsonicRepository
        val artists = runBlocking { repository.catalog(session.profile.id, ServerLibraryCategory.ARTISTS).artists }
        assertEquals("real fixture exposes all seven artists", 7, artists.size)
        val artist = artists[5]
        val albums = runBlocking { repository.artistAlbums(session.profile.id, artist.id).albums }
        assertEquals("selected fixture artist owns five albums", 5, albums.size)
        openServerTab(session)
        clickableText(text(CoreCommonR.string.server_tab_artists)).performClick()
        await { visibleOrNull(hasTestTag("serverArtist:${session.profile.id}:${artists.first().id}")) != null }
        visibleNode(hasSetTextAction()).performTextReplacement(Query)
        visibleNode(hasSetTextAction()).performImeAction()
        await { visibleOrNull(hasText(text(CoreCommonR.string.server_search_results, Query))) != null }
        val rootList = listContaining(visibleNode(hasSetTextAction()).fetchSemanticsNode())
        val artistTag = "serverArtist:${session.profile.id}:${artist.id}"
        rootList.performScrollToNode(hasTestTag(artistTag))
        val artistRow = unobscuredAppContentTarget { composeRule.onNodeWithTag(artistTag, useUnmergedTree = true) }
        val rootPosition = scrollPosition(rootList)
        capture(session, "artists-root")
        artistRow.performTouchInput { click() }
        val artistList = composeRule.onNodeWithTag("serverArtistAlbums", useUnmergedTree = true)
        await { artistList.isDisplayed() }
        val summary = localized.resources.getQuantityString(CoreCommonR.plurals.server_artist_album_count, 5, 5)
        await { visibleOrNull(hasText(summary)) != null }
        await { visibleOrNull(hasText(albums.first().name)) != null }
        assertTrue("artist detail must replace the library heading and platform tabs",
            visibleOrNull(libraryHeading()) == null && visibleOrNull(hasText(text(CoreCommonR.string.library_tab_local))) == null)
        albums.forEach { album ->
            artistList.performScrollToNode(hasText(album.name))
            visibleText(album.name).assertIsDisplayed()
        }
        val album = albums.last()
        val albumRow = unobscuredAppContentTarget { clickableText(album.name) }
        val artistPosition = scrollPosition(artistList)
        capture(session, "artist-albums")
        albumRow.performTouchInput { click() }
        awaitAlbumOpened(album)
        await { visibleCoverOrNull(album.name) != null }
        assertTrue("artist album must use the standalone shared detail page",
            visibleOrNull(libraryHeading()) == null && visibleOrNull(hasText(text(CoreCommonR.string.library_tab_local))) == null)
        val songs = runBlocking { repository.albumSongs(session.profile.id, album.id) }
        assertTrue("artist album contains real source tracks", songs.isNotEmpty())
        listContaining(checkNotNull(visibleCoverOrNull(album.name))).performScrollToNode(hasText(songs.first().name))
        visibleText(songs.first().name).assertIsDisplayed()
        capture(session, "artist-album-detail")
        assertAppChrome()
        Espresso.pressBack()
        await { artistList.isDisplayed() }
        assertEquals("back from album restores the artist's album-list position", artistPosition, scrollPosition(artistList), 2f)
        capture(session, "returned-artist")
        Espresso.pressBack()
        await { visibleOrNull(libraryHeading()) != null && visibleOrNull(hasTestTag(artistTag)) != null }
        val restoredRoot = listContaining(composeRule.onNodeWithTag(artistTag, useUnmergedTree = true).fetchSemanticsNode())
        assertEquals("back from artist restores root list position", rootPosition, scrollPosition(restoredRoot), 2f)
        restoredRoot.performScrollToNode(hasSetTextAction())
        visibleNode(hasSetTextAction()).assertTextContains(Query)
        restoredRoot.performScrollToNode(hasText(text(CoreCommonR.string.server_tab_artists)))
        clickableText(text(CoreCommonR.string.server_tab_artists)).assertIsSelected()
        capture(session, "returned-artists-root")
        assertAppChrome()
    }

    private fun exploreArtistNavigation(style: UiStyle, dark: Boolean = false) = withRealActivity(style, "explore-artist", dark = dark) { session ->
        fun dialogMatcher(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(isDialog())
        fun dialogText(value: String) = visibleNode(dialogMatcher(hasText(value)))
        fun tapDialogTarget(matcher: SemanticsMatcher) {
            val target = visibleNode(dialogMatcher(matcher)).fetchSemanticsNode()
            node(ancestorWith(target, SemanticsActions.OnClick).id).performTouchInput { click() }
        }
        val repository = AppContainer.subsonicRepository
        val artists = runBlocking { repository.catalog(session.profile.id, ServerLibraryCategory.ARTISTS).artists }
        assertEquals(7, artists.size)
        val artist = artists[5]
        val albums = runBlocking { repository.artistAlbums(session.profile.id, artist.id).albums }
        assertEquals(5, albums.size)
        val explore = visibleNode(hasContentDescription(text(Destinations.Explore.labelResId)) and
            hasAnyAncestor(hasTestTag("appBottomNavigation"))).fetchSemanticsNode()
        node(ancestorWith(explore, SemanticsActions.OnClick).id).performTouchInput { click() }
        await { visibleOrNull(hasText(text(CoreCommonR.string.server_my_music)) and !hasAnyAncestor(isDialog())) != null }
        clickableText(text(CoreCommonR.string.server_my_music)).performTouchInput { click() }
        await { visibleOrNull(dialogMatcher(hasText(text(CoreCommonR.string.server_tab_artists)))) != null }
        assertDialogSystemBars(dark)
        tapDialogTarget(hasText(text(CoreCommonR.string.server_tab_artists)))
        await { visibleOrNull(dialogMatcher(hasSetTextAction())) != null }
        visibleNode(dialogMatcher(hasSetTextAction())).performTextReplacement(Query)
        visibleNode(dialogMatcher(hasSetTextAction())).performImeAction()
        await { visibleOrNull(dialogMatcher(hasText(text(CoreCommonR.string.server_search_results, Query)))) != null }
        val rootList = listContaining(visibleNode(dialogMatcher(hasSetTextAction())).fetchSemanticsNode())
        val artistTag = "serverArtist:${session.profile.id}:${artist.id}"
        rootList.performScrollToNode(hasTestTag(artistTag))
        val rootPosition = scrollPosition(rootList)
        assertDialogSystemBars(dark)
        capture(session, "dialog-artists-root")
        tapDialogTarget(hasTestTag(artistTag))
        val artistList = composeRule.onNode(dialogMatcher(hasTestTag("serverArtistAlbums")), useUnmergedTree = true)
        await { artistList.isDisplayed() && visibleOrNull(dialogMatcher(hasText(albums.first().name))) != null }
        tapDialogTarget(hasContentDescription(text(CoreCommonR.string.server_search_artist_albums_hint)))
        await { visibleOrNull(dialogMatcher(hasSetTextAction())) != null }
        visibleNode(dialogMatcher(hasSetTextAction())).performTextReplacement(Query)
        albums.forEach { album ->
            artistList.performScrollToNode(hasText(album.name))
            dialogText(album.name).assertIsDisplayed()
        }
        val album = albums.last()
        val artistPosition = scrollPosition(artistList)
        assertDialogSystemBars(dark)
        capture(session, "dialog-artist-albums")
        tapDialogTarget(hasText(album.name))
        await { visibleCoverOrNull(album.name) != null }
        val songs = runBlocking { repository.albumSongs(session.profile.id, album.id) }
        assertTrue("dialog artist album contains real source tracks", songs.isNotEmpty())
        listContaining(checkNotNull(visibleCoverOrNull(album.name))).performScrollToNode(hasText(songs.first().name))
        dialogText(songs.first().name).assertIsDisplayed()
        assertDialogSystemBars(dark)
        capture(session, "dialog-album-detail")
        Espresso.pressBack()
        await { artistList.isDisplayed() && visibleOrNull(dialogMatcher(hasSetTextAction())) != null }
        assertEquals("dialog back restores artist album-list scroll", artistPosition, scrollPosition(artistList), 2f)
        visibleNode(dialogMatcher(hasSetTextAction())).assertTextContains(Query)
        assertDialogSystemBars(dark)
        capture(session, "dialog-returned-artist")
        Espresso.pressBack()
        await { artistList.isDisplayed() && visibleOrNull(dialogMatcher(hasSetTextAction())) == null }
        Espresso.pressBack()
        await { visibleOrNull(dialogMatcher(hasTestTag(artistTag))) != null }
        val restoredRoot = listContaining(visibleNode(dialogMatcher(hasTestTag(artistTag))).fetchSemanticsNode())
        assertEquals("dialog root restores artist-list scroll", rootPosition, scrollPosition(restoredRoot), 2f)
        restoredRoot.performScrollToNode(hasSetTextAction())
        visibleNode(dialogMatcher(hasSetTextAction())).assertTextContains(Query)
        restoredRoot.performScrollToNode(hasText(text(CoreCommonR.string.server_tab_artists)))
        node(ancestorWith(dialogText(text(CoreCommonR.string.server_tab_artists)).fetchSemanticsNode(), SemanticsActions.OnClick).id)
            .assertIsSelected()
        assertDialogSystemBars(dark)
        capture(session, "dialog-returned-root")
        tapDialogTarget(hasContentDescription(text(CoreCommonR.string.server_close)))
        await { composeRule.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        visibleText(text(CoreCommonR.string.server_my_music)).assertIsDisplayed()
        assertAppChrome()
        capture(session, "dialog-closed")
    }

    private fun downloadFromAlbumMenu() = withRealActivity(UiStyle.Clean, "download") { session ->
        GlobalDownloadManager.initialize(context)
        runBlocking {
            withTimeout(30_000L) { GlobalDownloadManager.startupProgressRestoreReady.await() }
            GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)
        }
        assertTrue("menu download fixture needs an empty download queue", GlobalDownloadManager.downloadTasks.value.isEmpty())
        assertTrue("menu download fixture needs no previous downloaded audio", GlobalDownloadManager.downloadedSongs.value.isEmpty())
        assertEquals("this navigation test uses the emulator's existing Wi-Fi", TrafficNetworkType.WIFI,
            context.currentDownloadNetworkTypeOrNull())
        openServerTab(session)
        val album = session.albums.first()
        val songs = runBlocking { AppContainer.subsonicRepository.albumSongs(session.profile.id, album.id) }
        assertEquals("real fixture's first album must contain two tracks", 2, songs.size)
        val keys = songs.map(SongItem::stableKey).toSet()
        session.downloadKeys += keys
        clickableText(album.name).performClick()
        awaitAlbumOpened(album)
        // 顶栏是当前页面最靠上的更多按钮，歌曲行的更多菜单不能代替整张专辑入口
        val more = composeRule.onAllNodesWithContentDescription(text(CoreCommonR.string.cd_more_actions), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { candidate -> runCatching { node(candidate.id).isDisplayed() }.getOrDefault(false) }
            .minBy { it.boundsInRoot.top }
        node(ancestorWith(more, SemanticsActions.OnClick).id).performClick()
        await { visibleOrNull(hasText(text(CoreCommonR.string.download_to_local))) != null }
        capture(session, "album-menu")
        clickableText(text(CoreCommonR.string.download_to_local)).performClick()
        await {
            visibleOrNull(hasText(text(CoreCommonR.string.traffic_risk_download_title))) != null ||
                GlobalDownloadManager.downloadTasks.value.any { it.song.stableKey() in keys } ||
                GlobalDownloadManager.downloadedSongs.value.any { it.stableKey in keys }
        }
        if (visibleOrNull(hasText(text(CoreCommonR.string.traffic_risk_download_title))) != null) {
            capture(session, "download-confirmation")
            clickableText(text(CoreCommonR.string.traffic_risk_download_confirm)).performClick()
        }
        capture(session, "download-queue")
        composeRule.waitUntil(180_000L) {
            val tasks = GlobalDownloadManager.downloadTasks.value.filter { it.song.stableKey() in keys }
            assertTrue("real album menu download must not fail", tasks.none { it.status == DownloadStatus.FAILED })
            keys.all { key -> GlobalDownloadManager.downloadedSongs.value.any { it.stableKey == key } } &&
                tasks.none { it.status in PendingStatuses }
        }
        keys.forEach { key ->
            val downloaded = GlobalDownloadManager.downloadedSongs.value.single { it.stableKey == key }
            assertTrue("menu action must publish actual non-empty audio", downloaded.fileSize > 0L)
            val reference = downloaded.mediaUri?.takeIf { Uri.parse(it).scheme in setOf("file", "content") }
                ?: downloaded.filePath
            val uri = Uri.parse(reference)
            val input = when (uri.scheme) {
                "content" -> checkNotNull(context.contentResolver.openInputStream(uri))
                "file" -> File(checkNotNull(uri.path)).inputStream()
                null -> File(reference).inputStream()
                else -> throw AssertionError("downloaded audio must use local storage")
            }
            input.use { assertTrue("published audio must be readable", it.read() >= 0) }
            session.completedDownloadCount++
        }
        capture(session, "download-complete")
    }

    private fun awaitAlbumOpened(album: ServerAlbum) {
        await {
            visibleCoverOrNull(album.name) != null ||
                visibleOrNull(hasText(text(CoreCommonR.string.server_search_album_tracks_hint))) != null
        }
        composeRule.waitForIdle()
    }

    private fun openServerTab(session: Session) {
        val label = text(CoreCommonR.string.server_my_music)
        val target = hasText(label)
        await { visibleOrNull(libraryHeading()) != null }
        if (visibleOrNull(target) == null) {
            val tabs = runCatching {
                composeRule.onNode(hasScrollToIndexAction() and
                    hasAnyDescendant(hasText(text(CoreCommonR.string.library_tab_local))), useUnmergedTree = true)
                    .fetchSemanticsNode()
            }.getOrNull()
            if (tabs != null) {
                node(tabs.id).performScrollToNode(hasText(label))
            } else {
                composeRule.onNode(target, useUnmergedTree = true).performScrollTo()
            }
        }
        clickableText(label).performClick()
        await { visibleOrNull(hasText(session.albums.first().name)) != null }
        assertAppChrome()
    }

    private fun assertAppChrome() {
        composeRule.onNodeWithTag("appMiniPlayer", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("appBottomNavigation", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun withRealActivity(style: UiStyle, phase: String, settingsStart: Boolean = false,
        dark: Boolean = false, action: (Session) -> Unit) {
        val themedPhase = "$phase-${if (dark) "dark" else "light"}"
        val fixture = isolatedFixture(style, themedPhase)
        val app = context.applicationContext as Application
        instrumentation.runOnMainSync { if (!AppContainer.isInitialized()) AppContainer.initialize(app) }
        val repository = AppContainer.subsonicRepository
        runBlocking { repository.accounts.load() }
        assumeTrue("layout fixture requires no existing server profiles", repository.accounts.profiles.value.isEmpty())
        assumeTrue("layout fixture requires no active playback", PlayerManager.currentQueueFlow.value.isEmpty() &&
            !AudioPlayerService.isInstanceActiveForDiagnostics())
        val queue = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
        val savedQueue = runBlocking { queue.readIfRoomPrimary() }
        assumeTrue("layout fixture requires an empty persisted queue", savedQueue?.playlist.isNullOrEmpty())
        val roomPrimary = runBlocking { queue.isRoomPrimary() }
        val savedLegacyQueue = LegacyQueueFiles.associateWith { name -> File(context.filesDir, name).takeIf(File::exists)?.readBytes() }
        assumeTrue("layout fixture does not overwrite an unmigrated legacy queue", roomPrimary || savedLegacyQueue.values.all { it == null })
        val settings = SettingsRepository(context)
        val savedSettings = runBlocking { context.dataStore.data.first() }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val savedMotion = NeriMotionStyle.clean
        val manager = context.packageManager
        val browser = ComponentName(context, CarMediaBrowserService::class.java)
        val browserState = manager.getComponentEnabledSetting(browser)
        var scenario: ActivityScenario<MainActivity>? = null
        var session: Session? = null
        var ownedProfileId: String? = null
        var ownedPlaybackKey: String? = null
        var failure: Throwable? = null
        try {
            manager.setComponentEnabledSetting(browser, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            grantRuntimePermissions(*playbackRuntimePermissions())
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                PreviewThemePromptRepository(context).answer(enable = false)
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setDefaultStartDestination(if (settingsStart) Destinations.Settings.route else Destinations.Library.route)
                settings.setUiStyle(style)
                settings.setThemeMode(followSystemDark = false, forceDark = dark)
                settings.setUiDensityScale(1f)
                settings.setKeepLastPlaybackProgress(true)
                settings.setUsbExclusivePlayback(false)
                repository.addAccount("PR511 real navigation fixture", fixture.baseUrl, fixture.username, fixture.password)
            }
            val profile = repository.accounts.profiles.value.single()
            ownedProfileId = profile.id
            val albums = runBlocking { repository.albums(profile.id, 0) }
            assertTrue("real fixture album page must load", albums.size >= 9)
            val song = runBlocking { repository.albumSongs(profile.id, albums.first().id) }.first()
            instrumentation.runOnMainSync { PlayerManager.release() }
            ownedPlaybackKey = song.stableKey()
            runBlocking { queue.replaceSnapshot(PersistedState(listOf(song.persisted()), 0, shouldResumePlayback = false)) }
            instrumentation.runOnMainSync { PlayerManager.initialize(app) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activeSession = Session(style, themedPhase, profile, albums, checkNotNull(scenario))
            activeSession.ownedPlaybackKeys += song.stableKey()
            session = activeSession
            await { composeRule.onNodeWithTag("appMiniPlayer", useUnmergedTree = true).isDisplayed() }
            action(activeSession)
            report(style, themedPhase, "pass", completedDownloads = activeSession.completedDownloadCount)
        } catch (error: Throwable) {
            failure = error
            session?.let { runCatching { capture(it, "failure") }.exceptionOrNull()?.let(error::addSuppressed) }
            report(style, themedPhase, "fail", error.javaClass.simpleName)
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(cleanupErrors::add) }
            session?.downloadKeys?.let { ownedKeys -> cleanup {
                GlobalDownloadManager.downloadTasks.value.filter {
                    it.song.stableKey() in ownedKeys && it.status in PendingStatuses
                }.forEach { GlobalDownloadManager.cancelDownloadTask(it.song.stableKey()) }
            } }
            cleanup { scenario?.close() }
            ownedPlaybackKey?.let { seedKey -> cleanup { instrumentation.runOnMainSync {
                val ownQueue = PlayerManager.currentQueueFlow.value
                val ownedKeys = session?.ownedPlaybackKeys.orEmpty() + seedKey
                check(ownQueue.all { it.stableKey() in ownedKeys }) {
                    "layout fixture must only clear its own playback queue"
                }
                if (ownQueue.isNotEmpty()) PlayerManager.removeQueueItems(ownQueue.indices.toSet())
                check(PlayerManager.currentQueueFlow.value.isEmpty()) { "layout fixture's seeded queue must be cleared" }
            } } }
            cleanup { instrumentation.runOnMainSync { context.stopService(Intent(context, AudioPlayerService::class.java)) } }
            cleanup { awaitPlaybackCleanup("service stop") { !AudioPlayerService.isInstanceActiveForDiagnostics() } }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { awaitPlaybackCleanup("player release") { !AudioPlayerService.isInstanceActiveForDiagnostics() &&
                PlayerManager.currentQueueFlow.value.isEmpty() && PlayerManager.currentSongFlow.value == null } }
            cleanup { runBlocking { if (savedQueue != null) queue.replaceSnapshot(savedQueue) else queue.clear() } }
            savedLegacyQueue.forEach { (name, bytes) -> cleanup {
                val file = File(context.filesDir, name)
                if (bytes != null) file.writeBytes(bytes) else check(!file.exists() || file.delete())
            } }
            session?.localPlaylistId?.let { id -> cleanup { runBlocking { LocalPlaylistRepository.getInstance(context).deletePlaylist(id) } } }
            ownedProfileId?.let { id -> cleanup { runBlocking { repository.accounts.remove(id) } } }
            cleanup { runBlocking { context.dataStore.updateData { savedSettings }; context.debugBuildWarningDataStore.updateData { savedWarning } } }
            cleanup { NeriMotionStyle.clean = savedMotion }
            cleanup { manager.setComponentEnabledSetting(browser, browserState, PackageManager.DONT_KILL_APP) }
            if (failure != null) cleanupErrors.forEach(failure::addSuppressed)
            else if (cleanupErrors.isNotEmpty()) {
                report(style, themedPhase, "fail", "FixtureCleanupError")
                throw AssertionError("Layout fixture cleanup failed", cleanupErrors.first())
            }
        }
    }

    private fun isolatedFixture(style: UiStyle, phase: String): Fixture {
        assumeTrue("real navigation fixture is opt-in", InstrumentationRegistry.getArguments().getString("navidromeFixture") == "true")
        assumeTrue("real navigation fixture requires a phone emulator", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish") && context.resources.configuration.smallestScreenWidthDp < 600)
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assumeTrue("real navigation fixture requires the dedicated AVD", avd == "NeriPlayer_PR511_Test")
        resultFile(style, phase).let { check(!it.exists() || it.delete()) }
        val file = File(context.cacheDir, "navidrome-test.json")
        assumeTrue("fixture credentials must be injected into private cache", file.isFile)
        val json = try { JSONObject(file.readText()) } catch (_: Exception) { throw AssertionError("Invalid private fixture JSON") }
        val uri = Uri.parse(json.optString("baseUrl"))
        assumeTrue("fixture accepts only this task's local Navidrome", uri.scheme == "http" && uri.host == "10.0.2.2" &&
            uri.port == 45331 && uri.path == "/music/" && uri.query == null && uri.userInfo == null && uri.fragment == null)
        val username = json.optString("username")
        val password = json.optString("password")
        assumeTrue("private fixture credentials are incomplete", username.isNotBlank() && password.isNotEmpty())
        return Fixture(uri.toString(), username, password)
    }

    private fun visibleText(value: String): SemanticsNodeInteraction = visibleNode(hasText(value))
    private fun libraryHeading() = hasText(text(CoreCommonR.string.library_title)) and
        !hasAnyAncestor(hasTestTag("appBottomNavigation"))
    private fun clickableText(value: String) = node(ancestorWith(visibleText(value).fetchSemanticsNode(), SemanticsActions.OnClick).id)
    private fun visibleNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val matchingNode = checkNotNull(visibleOrNull(matcher)) { "Expected one visible matching UI node" }
        return node(matchingNode.id)
    }
    private fun visibleOrNull(matcher: SemanticsMatcher): SemanticsNode? =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f && runCatching { node(it.id).isDisplayed() }.getOrDefault(false) }
    private fun visibleCoverOrNull(description: String): SemanticsNode? =
        composeRule.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { runCatching { node(it.id).isDisplayed() }.getOrDefault(false) }
    private fun node(id: Int) = composeRule.onNode(SemanticsMatcher("fixture UI node") { it.id == id }, useUnmergedTree = true)
    private fun clickableBounds(start: SemanticsNode): Rect = ancestorWith(start, SemanticsActions.OnClick).boundsInRoot
    private fun listContaining(start: SemanticsNode) = node(ancestorWith(start, SemanticsActions.ScrollToIndex).id)
    private fun <T> ancestorWith(start: SemanticsNode, key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): SemanticsNode {
        var current: SemanticsNode? = start
        while (current != null) {
            if (current.config.getOrNull(key) != null) return current
            current = current.parent
        }
        throw AssertionError("Expected production UI ancestor semantics")
    }
    private fun scrollPosition(list: SemanticsNodeInteraction): Float =
        checkNotNull(list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
    private fun text(resource: Int, vararg arguments: Any): String = localized.getString(resource, *arguments)
    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
    private fun await(condition: () -> Boolean) = composeRule.waitUntil(30_000L) { runCatching(condition).getOrDefault(false) }

    private fun awaitPlaybackCleanup(stage: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(10_000L) { condition() }
        } catch (error: Throwable) {
            throw AssertionError("Layout fixture $stage failed: " +
                "serviceInactive=${!AudioPlayerService.isInstanceActiveForDiagnostics()}, " +
                "queueEmpty=${PlayerManager.currentQueueFlow.value.isEmpty()}, " +
                "currentSongNull=${PlayerManager.currentSongFlow.value == null}", error)
        }
    }

    private fun assertDialogSystemBars(dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw AssertionError("dialog window inspection requires Android 10 or later")
        var appearance: Pair<Boolean, Boolean>? = null
        instrumentation.runOnMainSync {
            fun dialogWindow(view: View): Window? {
                if (view is DialogWindowProvider) return view.window
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index) ?: continue
                    dialogWindow(child)?.let { return it }
                }
                return null
            }
            val window = checkNotNull(WindowInspector.getGlobalWindowViews()
                .filter { it.isAttachedToWindow && it.isShown && it.hasWindowFocus() }
                .mapNotNull(::dialogWindow).distinct().singleOrNull()) { "expected one focused real Compose dialog window" }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            appearance = controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
        }
        val actual = checkNotNull(appearance)
        assertEquals("dialog status-bar icon appearance must follow the app's forced theme", !dark, actual.first)
        assertEquals("dialog navigation-bar icon appearance must follow the app's forced theme", !dark, actual.second)
    }

    private fun unobscuredAppContentTarget(findTarget: () -> SemanticsNodeInteraction): SemanticsNodeInteraction {
        var interaction = findTarget()
        interaction.performScrollTo()
        for (attempt in 0..3) {
            val target = interaction.fetchSemanticsNode()
            val scrollable = ancestorWith(target, SemanticsActions.ScrollBy)
            val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val miniPlayer = composeRule.onNodeWithTag("appMiniPlayer", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val navigation = composeRule.onNodeWithTag("appBottomNavigation", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val safeTop = maxOf(root.top, scrollable.boundsInRoot.top) + dp(8f)
            val safeBottom = minOf(root.bottom, scrollable.boundsInRoot.bottom, miniPlayer.top, navigation.top) - dp(8f)
            val bounds = target.boundsInRoot
            check(bounds.height > 0f && bounds.height <= safeBottom - safeTop) {
                "scrollable target must fit above app playback controls"
            }
            if (bounds.top >= safeTop && bounds.bottom <= safeBottom) return interaction
            check(attempt < 3) { "scrollable target is still obscured after three scrolls: $bounds in $safeTop..$safeBottom" }
            val delta = if (bounds.bottom > safeBottom) bounds.bottom - safeBottom else bounds.top - safeTop
            node(scrollable.id).performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                check(scroll(0f, delta)) { "list cannot scroll the target into an unobscured area" }
            }
            composeRule.waitForIdle()
            interaction = findTarget()
        }
        throw AssertionError("scrollable target was not safely positioned")
    }

    private fun capture(session: Session, stage: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMusicServerUi") != "true") return
        var hostView: View? = null
        session.scenario.onActivity { hostView = it.window.decorView }
        val bitmap = composeRule.captureMusicServerBitmap(checkNotNull(hostView))
        try {
            File(context.cacheDir, "navidrome-main-${session.style.storageValue}-${session.phase}-$stage.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    private fun report(style: UiStyle, phase: String, status: String, errorType: String? = null, completedDownloads: Int = 0) {
        resultFile(style, phase).writeText(JSONObject().put("status", status).put("style", style.storageValue)
            .put("phase", phase).put("dark", phase.endsWith("-dark")).put("errorType", errorType)
            .put("completedDownloads", completedDownloads).toString())
    }
    private fun resultFile(style: UiStyle, phase: String) = File(context.cacheDir, "navidrome-main-${style.storageValue}-$phase.json")
    private fun SongItem.persisted() = PersistedSongItem(id, name, artist, album, albumId, durationMs, coverUrl,
        mediaUri = mediaUri, channelId = channelId, audioId = audioId)
    private class Fixture(val baseUrl: String, val username: String, val password: String)
    private class Session(val style: UiStyle, val phase: String, val profile: SubsonicProfile,
        val albums: List<ServerAlbum>, val scenario: ActivityScenario<MainActivity>,
        var localPlaylistId: Long? = null, val downloadKeys: MutableSet<String> = mutableSetOf(),
        var completedDownloadCount: Int = 0, val ownedPlaybackKeys: MutableSet<String> = mutableSetOf())
    private companion object {
        const val Query = "PR511"
        val LegacyQueueFiles = listOf("last_playlist.json", "last_playback_state.json")
        val PendingStatuses = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING_NETWORK)
    }
}
