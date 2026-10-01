package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCoroutinesApi::class)
class NeteaseArtistDetailViewModelFollowTest {
    private val viewModels = mutableListOf<NeteaseArtistDetailViewModel>()

    @After
    fun resetDispatcher() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `late favorites load refreshes the current artist and tracks later removals`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(dispatcher)
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        testScheduler.runCurrent()
        assertFalse(fixture.viewModel.uiState.value.header!!.followed)

        fixture.favorites.value = listOf(favorite(1L), favorite(2L))
        testScheduler.runCurrent()
        assertTrue(fixture.viewModel.uiState.value.header!!.followed)

        fixture.viewModel.start(NeteaseArtistSummary(2L, "Artist 2"))
        testScheduler.runCurrent()
        fixture.favorites.value = listOf(favorite(1L))
        testScheduler.runCurrent()
        assertEquals(2L, fixture.viewModel.uiState.value.header!!.id)
        assertFalse(fixture.viewModel.uiState.value.header!!.followed)
    }

    @Test
    fun `toggle waits for initialization and reverses loaded state instead of the stale header`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(dispatcher)
        var initialized = false
        var completion: Continuation<Boolean>? = null
        doAnswer { invocation ->
            if (initialized) true else {
                // continuation 的泛型已擦除，这个 suspend 方法固定返回 Boolean
                @Suppress("UNCHECKED_CAST")
                val pending = invocation.rawArguments.last() as Continuation<Boolean>
                completion = pending
                COROUTINE_SUSPENDED
            }
        }.`when`(fixture.repository).awaitInitialized()
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        testScheduler.runCurrent()
        fixture.viewModel.toggleFollow()
        testScheduler.runCurrent()
        assertTrue(fixture.viewModel.uiState.value.followUpdating)
        assertTrue(fixture.removedIds.isEmpty())

        fixture.favorites.value = listOf(favorite(1L))
        initialized = true
        requireNotNull(completion).resume(true)
        testScheduler.runCurrent()

        assertEquals(listOf(1L), fixture.removedIds)
        assertFalse(fixture.viewModel.uiState.value.header!!.followed)
        assertFalse(fixture.viewModel.uiState.value.followUpdating)
        assertNull(fixture.viewModel.uiState.value.error)
    }

    @Test
    fun `initialization failure leaves follow state unchanged and reports failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(dispatcher)
        `when`(fixture.repository.awaitInitialized()).thenReturn(false)
        fixture.favorites.value = listOf(favorite(1L))
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        testScheduler.runCurrent()

        fixture.viewModel.toggleFollow()
        testScheduler.runCurrent()

        assertTrue(fixture.viewModel.uiState.value.header!!.followed)
        assertTrue(fixture.removedIds.isEmpty())
        assertFalse(fixture.viewModel.uiState.value.followUpdating)
        assertEquals("Follow failed", fixture.viewModel.uiState.value.error)
    }

    @Test
    fun `late follow success cannot overwrite the artist opened during the write`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(dispatcher)
        fixture.favorites.value = listOf(favorite(1L), favorite(2L))
        var completion: Continuation<Unit>? = null
        doAnswer { invocation ->
            // continuation 的泛型已擦除，这个 suspend 方法固定返回 Unit
            @Suppress("UNCHECKED_CAST")
            val pending = invocation.rawArguments.last() as Continuation<Unit>
            completion = pending
            COROUTINE_SUSPENDED
        }.`when`(fixture.repository).removeFavorite(1L, FAVORITE_SOURCE_NETEASE_ARTIST)
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        testScheduler.runCurrent()
        fixture.viewModel.toggleFollow()
        testScheduler.runCurrent()
        fixture.viewModel.start(NeteaseArtistSummary(2L, "Artist 2"))
        testScheduler.runCurrent()

        fixture.favorites.value = listOf(favorite(2L))
        requireNotNull(completion).resume(Unit)
        testScheduler.runCurrent()

        assertEquals(2L, fixture.viewModel.uiState.value.header!!.id)
        assertTrue(fixture.viewModel.uiState.value.header!!.followed)
        assertFalse(fixture.viewModel.uiState.value.followUpdating)
        assertNull(fixture.viewModel.uiState.value.error)
    }

    @Test
    fun `late follow failure cannot add an error to another artist`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(dispatcher)
        fixture.favorites.value = listOf(favorite(1L))
        var completion: Continuation<Unit>? = null
        doAnswer { invocation ->
            // continuation 的泛型已擦除，这个 suspend 方法固定返回 Unit
            @Suppress("UNCHECKED_CAST")
            val pending = invocation.rawArguments.last() as Continuation<Unit>
            completion = pending
            COROUTINE_SUSPENDED
        }.`when`(fixture.repository).removeFavorite(1L, FAVORITE_SOURCE_NETEASE_ARTIST)
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        testScheduler.runCurrent()
        fixture.viewModel.toggleFollow()
        testScheduler.runCurrent()
        fixture.viewModel.start(NeteaseArtistSummary(2L, "Artist 2"))
        testScheduler.runCurrent()

        requireNotNull(completion).resumeWithException(IOException("late failure"))
        testScheduler.runCurrent()

        assertEquals(2L, fixture.viewModel.uiState.value.header!!.id)
        assertFalse(fixture.viewModel.uiState.value.header!!.followed)
        assertFalse(fixture.viewModel.uiState.value.followUpdating)
        assertNull(fixture.viewModel.uiState.value.error)
    }

    private suspend fun fixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher): Fixture {
        val application = mock(Application::class.java)
        `when`(application.getString(eq(CoreCommonR.string.artist_follow_failed), anyString()))
            .thenReturn("Follow failed")
        val client = mock(NeteaseClient::class.java)
        `when`(client.getArtistDetail(anyLong())).thenAnswer {
            val id = it.getArgument<Long>(0)
            """{"code":200,"artist":{"id":$id,"name":"Artist $id"}}"""
        }
        `when`(client.getArtistSongs(anyLong(), anyString(), anyInt(), anyInt()))
            .thenReturn("""{"code":200,"songs":[]}""")
        `when`(client.getArtistAlbums(anyLong(), anyInt(), anyInt()))
            .thenReturn("""{"code":200,"hotAlbums":[]}""")
        val favorites = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
        val repository = mock(FavoritePlaylistRepository::class.java)
        `when`(repository.favorites).thenReturn(favorites)
        `when`(repository.isFavorite(
            anyLong(),
            eq(FAVORITE_SOURCE_NETEASE_ARTIST) ?: FAVORITE_SOURCE_NETEASE_ARTIST
        )).thenAnswer {
            val id = it.getArgument<Long>(0)
            favorites.value.any { favorite -> favorite.id == id }
        }
        `when`(repository.awaitInitialized()).thenReturn(true)
        val removedIds = mutableListOf<Long>()
        doAnswer { invocation ->
            val id = invocation.getArgument<Long>(0)
            removedIds += id
            favorites.value = favorites.value.filterNot { it.id == id }
            Unit
        }.`when`(repository).removeFavorite(
            anyLong(),
            eq(FAVORITE_SOURCE_NETEASE_ARTIST) ?: FAVORITE_SOURCE_NETEASE_ARTIST
        )
        val viewModel = NeteaseArtistDetailViewModel(application, client, repository, dispatcher)
        viewModels += viewModel
        return Fixture(viewModel, repository, favorites, removedIds)
    }

    private data class Fixture(
        val viewModel: NeteaseArtistDetailViewModel,
        val repository: FavoritePlaylistRepository,
        val favorites: MutableStateFlow<List<FavoritePlaylist>>,
        val removedIds: MutableList<Long>
    )

    private fun favorite(id: Long) = FavoritePlaylist(
        id = id,
        name = "Artist $id",
        coverUrl = null,
        trackCount = 0,
        source = FAVORITE_SOURCE_NETEASE_ARTIST,
        songs = emptyList()
    )
}
