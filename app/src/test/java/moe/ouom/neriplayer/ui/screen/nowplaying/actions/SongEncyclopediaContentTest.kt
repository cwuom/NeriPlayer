package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaBasicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCreditRole
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCredits
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaMusicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import moe.ouom.neriplayer.platform.netease.encyclopedia.SongEncyclopediaRepository
import moe.ouom.neriplayer.platform.netease.encyclopedia.SongEncyclopediaResult
import moe.ouom.neriplayer.ui.viewmodel.CommentViewModel
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.SongEncyclopediaUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
class SongEncyclopediaContentTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private var openedComments: CommentSource? = null

    @Before
    fun initialize() {
        if (!AppContainer.isInitialized()) AppContainer.initialize(context)
    }

    @After
    fun clearViewModels() {
        composeRule.runOnIdle { owner.viewModelStore.clear() }
    }

    @Test
    fun `netease preview opens the actual resolved comment source`() {
        render(song = neteaseSong(186016L))

        composeRule.onNodeWithText(context.getString(AppR.string.encyclopedia_section_comments))
            .assertExists()
        composeRule.onNodeWithText(context.getString(AppR.string.encyclopedia_comment_show_all))
            .performScrollTo().performClick()

        assertEquals(CommentPlatform.NETEASE, openedComments?.platform)
        assertEquals(186016L, openedComments?.resourceId)
    }

    @Test
    fun `bilibili source does not render the netease preview`() {
        render(song = neteaseSong(1700010003L).copy(channelId = "bilibili", album = "Bilibili"))
        assertNoCommentPreview()
    }

    @Test
    fun `local song does not render a comment preview`() {
        render()
        assertNoCommentPreview()
    }

    @Test
    fun `zero netease id is rejected before a comment preview is created`() {
        render(song = neteaseSong(0L))
        assertNoCommentPreview()
    }

    @Test
    fun `negative netease id is rejected before a comment preview is created`() {
        render(song = neteaseSong(-1L))
        assertNoCommentPreview()
    }

    @Test
    fun `expanding song detail shows each existing metadata value once`() {
        render(metadata = true)
        expandSongDetail()

        listOf("Fixture album", "Fixture genre", "Fixture language", "137", "Fixture lyricist",
            "Fixture composer", "Fixture arranger", "Fixture producer").forEach { value ->
            composeRule.onAllNodesWithText(value, useUnmergedTree = true).assertCountEquals(1)
        }
    }

    @Test
    fun `metadata deduplication preserves basic info tags roles release details and awards`() {
        render(metadata = true)
        expandSongDetail()

        listOf("Fixture song", "Fixture artist", "Fixture tag", "Fixture engineer", "Recording",
            "Fixture label", "2024-01-02", "Fixture award", "Fixture category", "Fixture media")
            .forEach { value ->
                composeRule.onAllNodesWithText(value, useUnmergedTree = true).assertCountEquals(1)
            }
    }

    private fun expandSongDetail() {
        composeRule.onNodeWithText(context.getString(AppR.string.encyclopedia_action_expand))
            .performScrollTo().performClick()
    }

    private fun assertNoCommentPreview() {
        composeRule.onNodeWithText(context.getString(AppR.string.encyclopedia_section_comments))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(AppR.string.encyclopedia_comment_show_all))
            .assertDoesNotExist()
    }

    private fun render(song: SongItem = localSong(), metadata: Boolean = false) {
        val provider = ViewModelProvider(owner.viewModelStore, ViewModelProvider.NewInstanceFactory())
        val viewModel = provider[NowPlayingViewModel::class.java]
        viewModel.encyclopediaRepository = object : SongEncyclopediaRepository {
            override suspend fun getEncyclopedia(song: SongItem) = SongEncyclopediaResult.Success(
                SongEncyclopedia(
                    songKey = song.encyclopediaSongKey(),
                    source = MusicPlatform.CLOUD_MUSIC,
                    basicInfo = EncyclopediaBasicInfo("Fixture song", listOf("Fixture artist"), "Fixture album"),
                    musicInfo = if (metadata) EncyclopediaMusicInfo(
                        genres = listOf("Fixture genre"), language = "Fixture language", bpm = 137,
                        tags = listOf("Fixture tag")
                    ) else null,
                    credits = if (metadata) EncyclopediaCredits(
                        lyricists = listOf("Fixture lyricist"), composers = listOf("Fixture composer"),
                        arrangers = listOf("Fixture arranger"), producers = listOf("Fixture producer"),
                        otherRoles = listOf(EncyclopediaCreditRole("Recording", listOf("Fixture engineer")))
                    ) else null,
                    publishTimeMs = if (metadata) 1_704_196_800_000L else null,
                    albumCompany = if (metadata) "Fixture label" else null,
                    awards = if (metadata) listOf(EncyclopediaAward("Fixture award", "Fixture category")) else emptyList(),
                    appearances = if (metadata) listOf("Fixture media") else emptyList(),
                    fetchedAtMs = 0L
                )
            )
        }
        provider["encyclopedia_comment_preview", CommentViewModel::class.java].repositoryFactory = {
            EmptyCommentsRepository
        }
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    SongEncyclopediaContent(viewModel, song, onDismiss = {}, onOpenComments = { openedComments = it })
                }
            }
        }
        composeRule.waitUntil { viewModel.encyclopediaState.value is SongEncyclopediaUiState.Success }
        composeRule.waitForIdle()
    }

    private object EmptyCommentsRepository : CommentRepository {
        override val platform = CommentPlatform.NETEASE

        override suspend fun loadComments(
            source: CommentSource, page: Int, pageSize: Int, forceRefresh: Boolean,
            sort: CommentSort, cursor: String?
        ) = CommentPage(emptyList(), page, pageSize, total = 0L, hasMore = false)

        override suspend fun loadReplies(
            source: CommentSource, rootId: String, page: Int, pageSize: Int, cursor: String?
        ): CommentPage = error("preview policy must not load replies")

        override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) =
            error("preview policy must not change likes")

        override suspend fun sendComment(source: CommentSource, content: String, target: CommentReplyTarget?) =
            error("preview policy must not send comments")
    }

    private fun neteaseSong(id: Long) = SongItem(
        id, "Song$id", "Artist", "Album", 0L, 200_000L, null,
        channelId = "netease", audioId = id.toString()
    )

    private fun localSong() = SongItem(
        42L, "Local", "Local artist", "Local album", 0L, 180_000L, null,
        mediaUri = "content://media/42"
    )
}
