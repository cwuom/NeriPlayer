package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemsPage
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeMusicCreatorDetailPlaybackTest {

    private val application: Application = mock(Application::class.java) { invocation ->
        if (invocation.method.name == "getString") invocation.arguments.joinToString("|") else null
    }
    private val viewModels = mutableListOf<YouTubeMusicCreatorDetailViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `playing a section song loads the remaining pages and emits the queue`() = runTest {
        val viewModel = loadedViewModel()
        val requestedTitles = mutableListOf<String>()
        viewModel.fetchCreatorItems = { _, title ->
            requestedTitles += title
            YouTubeMusicCreatorItemsPage(title = title, items = listOf(song(2)), continuation = "next")
        }
        viewModel.fetchCreatorItemsContinuation = {
            YouTubeMusicCreatorItemsPage(title = "Songs", items = listOf(song(3)))
        }
        val section = YouTubeMusicCreatorSection(
            title = "Songs",
            items = listOf(song(1)),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint("UC_a", "songs")
        )
        val queue = async { viewModel.playbackRequests.first() }
        runCurrent()

        viewModel.playSectionSong(section, song(2))

        val emitted = queue.await()
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), emitted.songs.map { it.name })
        assertEquals(1, emitted.startIndex)
        assertEquals(listOf("Songs"), requestedTitles)
        runCurrent()
        assertNull(viewModel.uiState.value.playbackQueueLoadingSectionKey)
    }

    @Test
    fun `a section without playable songs reports a section error`() = runTest {
        val viewModel = loadedViewModel()
        val section = YouTubeMusicCreatorSection(title = "Albums", items = listOf(song(1).copy(videoId = "")))

        viewModel.playSectionSong(section, section.items.first())

        val state = viewModel.uiState.first { it.playbackQueueError != null }
        assertEquals(youtubeMusicCreatorSectionKey(section), state.playbackQueueErrorSectionKey)
        assertEquals(
            "${CoreCommonR.string.youtube_creator_items_load_failed}|Albums|No playable YouTube Music items",
            state.playbackQueueError
        )
        assertNull(state.playbackQueueLoadingSectionKey)
    }

    @Test
    fun `a slow section load shows loading and ignores taps until the queue arrives`() = runTest {
        val viewModel = loadedViewModel()
        val gate = CompletableDeferred<Unit>()
        val requestedTitles = mutableListOf<String>()
        viewModel.fetchCreatorItems = { _, title ->
            requestedTitles += title
            gate.await()
            YouTubeMusicCreatorItemsPage(title = title, items = listOf(song(2)))
        }
        val section = YouTubeMusicCreatorSection(
            title = "Songs",
            items = listOf(song(1)),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint("UC_a", "songs")
        )
        val otherSection = section.copy(title = "Singles")

        viewModel.playSectionSong(section, song(1))
        runCurrent()
        assertEquals(youtubeMusicCreatorSectionKey(section), viewModel.uiState.value.playbackQueueLoadingSectionKey)

        viewModel.playSectionSong(otherSection, song(1))
        val queue = async { viewModel.playbackRequests.first() }
        runCurrent()
        gate.complete(Unit)

        assertEquals(listOf("Song 1", "Song 2"), queue.await().songs.map { it.name })
        assertEquals(listOf("Songs"), requestedTitles)
        runCurrent()
        assertNull(viewModel.uiState.value.playbackQueueLoadingSectionKey)
    }

    private suspend fun TestScope.loadedViewModel(): YouTubeMusicCreatorDetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = YouTubeMusicCreatorDetailViewModel(application) { creator -> detail(creator) }
            .also(viewModels::add)
        viewModel.start(creator("UC_a"))
        viewModel.uiState.first { !it.loading }
        return viewModel
    }

    private fun song(index: Int) = YouTubeMusicCreatorItem(
        type = YouTubeMusicCreatorItemType.Song,
        title = "Song $index",
        subtitle = "Artist",
        coverUrl = "",
        videoId = "video-$index"
    )

    private fun creator(browseId: String) = YouTubeMusicCreatorSummary(
        browseId = browseId,
        title = "Creator $browseId",
        subtitle = "",
        coverUrl = ""
    )

    private fun detail(creator: YouTubeMusicCreatorSummary) = YouTubeMusicCreatorDetail(
        header = YouTubeMusicCreatorHeader(
            browseId = creator.browseId,
            title = creator.title,
            subtitle = creator.subtitle,
            coverUrl = creator.coverUrl
        ),
        sections = emptyList()
    )
}
