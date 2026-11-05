package moe.ouom.neriplayer.ui.viewmodel.server

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseKey
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowsePage
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseCache
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import moe.ouom.neriplayer.common.R as CoreCommonR

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerArtistDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = SubsonicProfile("server-a", "A", "https://example.test/", "user")
    private val otherProfile = profile.copy(id = "server-b")
    private val artist = ServerArtist(profile.id, "same-artist", "Artist", null, 2)
    private val albums = listOf(
        ServerAlbum(profile.id, "first", "First", "Artist", null, 1),
        ServerAlbum(profile.id, "second", "Second", "Artist", null, 2)
    )
    private lateinit var profiles: MutableStateFlow<List<SubsonicProfile>>
    private lateinit var repository: SubsonicRepository
    private val artworkUpdates = MutableStateFlow(0L)
    private var resolveArtwork: (ServerArtist) -> ServerArtist = { it }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        profiles = MutableStateFlow(listOf(profile, otherProfile))
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(profiles)
        `when`(accounts.profile(profile.id)).thenAnswer { profiles.value.firstOrNull { it.id == profile.id && it.enabled } }
        `when`(accounts.profile(otherProfile.id)).thenAnswer { profiles.value.firstOrNull { it.id == otherProfile.id && it.enabled } }
        repository = mock(SubsonicRepository::class.java) { invocation ->
            when (invocation.method.name) {
                "cachedArtist" -> resolveArtwork(invocation.getArgument(0))
                "cachedArtists" -> invocation.getArgument<List<ServerArtist>>(1).map(resolveArtwork)
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        `when`(repository.accounts).thenReturn(accounts)
        val cache = mock(SubsonicBrowseCache::class.java)
        `when`(cache.updates).thenReturn(artworkUpdates)
        `when`(repository.browseCache).thenReturn(cache)
        listOf(profile, otherProfile).forEach { selected ->
            `when`(repository.artistAlbumsKey(selected.id, artist.id)).thenAnswer {
                ServerBrowseKey(selected.id, accounts.profile(selected.id)?.revision ?: -1L,
                    "artist", artist.id, size = 500)
            }
        }
    }

    @Test fun `song catalog arriving after navigation updates avatar without reloading artist albums`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(ServerBrowsePage(albums = albums))
        val vm = ServerArtistDetailViewModel(repository)
        backgroundScope.launch { vm.observeArtwork() }
        try {
            vm.start(artist)
            runCurrent()
            assertEquals(null, vm.state.value.artist?.imageUrl)
            resolveArtwork = { it.copy(fallbackCoverUrl = "https://example.test/last-song.jpg") }
            artworkUpdates.value++
            runCurrent()
            assertEquals("https://example.test/last-song.jpg", vm.state.value.artist?.imageUrl)
            assertEquals(albums, vm.state.value.albums)
            verify(repository, times(1)).artistAlbums(profile.id, artist.id, false)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `artist response server avatar updates the displayed artist summary`() = runTest(dispatcher) {
        val serverArtist = artist.copy(coverUrl = "https://example.test/server-avatar.jpg")
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(
            ServerBrowsePage(albums = albums, artists = listOf(serverArtist)))
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            assertEquals(serverArtist, vm.state.value.artist)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `leaving an artist stops cache hydration by the retained view model`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(ServerBrowsePage(albums = albums))
        val vm = ServerArtistDetailViewModel(repository)
        val observer = backgroundScope.launch { vm.observeArtwork() }
        try {
            vm.start(artist)
            runCurrent()
            observer.cancel()
            runCurrent()
            val reads = mockingDetails(repository).invocations.count { it.method.name == "cachedArtist" }
            artworkUpdates.value++
            runCurrent()
            assertEquals(reads, mockingDetails(repository).invocations.count { it.method.name == "cachedArtist" })
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `failed cold restore clears a fallback from an older catalog`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenThrow(IllegalStateException("offline"))
        resolveArtwork = { it.copy(fallbackCoverUrl = null) }
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist.copy(fallbackCoverUrl = "https://example.test/old-revision-cover"))
            runCurrent()
            assertEquals(null, vm.state.value.artist?.fallbackCoverUrl)
            assertEquals(CoreCommonR.string.server_request_failed, vm.state.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `loads complete artist discography in source order and retry refreshes the same reference`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(ServerBrowsePage(albums = albums))
        `when`(repository.artistAlbums(profile.id, artist.id, true)).thenReturn(ServerBrowsePage(albums = albums.reversed()))
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            assertEquals(albums, vm.state.value.albums)
            assertFalse(vm.state.value.loading)
            vm.retry()
            runCurrent()
            assertEquals(albums.reversed(), vm.state.value.albums)
            assertEquals(artist, vm.state.value.artist)
            verify(repository).artistAlbums(profile.id, artist.id, true)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `reopening the same artist checks repository freshness again`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(
            ServerBrowsePage(albums = albums), ServerBrowsePage(albums = albums.reversed())
        )
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            assertEquals(albums, vm.state.value.albums)
            vm.start(artist)
            runCurrent()
            assertEquals(albums.reversed(), vm.state.value.albums)
            verify(repository, times(2)).artistAlbums(profile.id, artist.id, false)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `account load failure before start stays retryable instead of loading forever`() = runTest(dispatcher) {
        var fail = true
        val accounts = repository.accounts
        doAnswer {
            if (fail) throw IllegalStateException("unavailable")
            Unit
        }.`when`(accounts).load()
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(ServerBrowsePage(albums = albums))
        val vm = ServerArtistDetailViewModel(repository)
        try {
            runCurrent()
            assertEquals(CoreCommonR.string.server_error_load_accounts, vm.state.value.error)
            vm.start(artist)
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals(CoreCommonR.string.server_error_load_accounts, vm.state.value.error)
            fail = false
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
            assertEquals(albums, vm.state.value.albums)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `deleting selected profile keeps original reference and clears its albums`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenReturn(ServerBrowsePage(albums = albums))
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            profiles.value = listOf(otherProfile)
            runCurrent()
            assertEquals(artist, vm.state.value.artist)
            assertTrue(vm.state.value.albums.isEmpty())
            assertEquals(CoreCommonR.string.server_unavailable, vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `artist failure is visible and retry loads the original full discography`() = runTest(dispatcher) {
        `when`(repository.artistAlbums(profile.id, artist.id, false)).thenThrow(IllegalStateException("unavailable"))
        `when`(repository.artistAlbums(profile.id, artist.id, true)).thenReturn(ServerBrowsePage(albums = albums))
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            assertEquals(CoreCommonR.string.server_request_failed, vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.albums.isEmpty())
            vm.retry()
            runCurrent()
            assertEquals(null, vm.state.value.error)
            assertEquals(albums, vm.state.value.albums)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `late response cannot replace another server artist with the same artist id`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).artistAlbums(profile.id, artist.id, false)
        val otherArtist = artist.copy(profileId = otherProfile.id)
        `when`(repository.artistAlbums(otherProfile.id, artist.id, false)).thenReturn(ServerBrowsePage(albums = albums.reversed()))
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            vm.start(otherArtist)
            runCurrent()
            response.resume(ServerBrowsePage(albums = albums))
            runCurrent()
            assertEquals(otherArtist, vm.state.value.artist)
            assertEquals(albums.reversed(), vm.state.value.albums)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `configuration revision invalidates old artist response`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).artistAlbums(profile.id, artist.id, false)
        val revised = profile.copy(revision = 1L)
        val vm = ServerArtistDetailViewModel(repository)
        try {
            vm.start(artist)
            runCurrent()
            doReturn(ServerBrowsePage(albums = albums.reversed())).`when`(repository)
                .artistAlbums(profile.id, artist.id, false)
            profiles.value = listOf(revised, otherProfile)
            runCurrent()
            response.resume(ServerBrowsePage(albums = albums))
            runCurrent()
            assertEquals(1L, vm.state.value.revision)
            assertEquals(albums.reversed(), vm.state.value.albums)
        } finally { vm.viewModelScope.cancel() }
    }
}
