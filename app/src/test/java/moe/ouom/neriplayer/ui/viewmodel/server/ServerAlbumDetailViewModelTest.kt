package moe.ouom.neriplayer.ui.viewmodel.server

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseKey
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowsePage
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseStore
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseCache
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import moe.ouom.neriplayer.common.R as CoreCommonR

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerAlbumDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = SubsonicProfile("server-a", "A", "https://example.test/", "user")
    private val otherProfile = profile.copy(id = "server-b")
    private val album = ServerAlbum(profile.id, "same-album", "Album", "Artist", null, 2)
    private val key = ServerBrowseKey(profile.id, profile.revision, "album", album.id)
    private val songs = listOf(
        SongItem(1L, "First", "Artist", "Album", 0L, 60_000L, null),
        SongItem(2L, "Second", "Artist", "Album", 0L, 60_000L, null)
    )
    private lateinit var profiles: MutableStateFlow<List<SubsonicProfile>>
    private lateinit var repository: SubsonicRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        profiles = MutableStateFlow(listOf(profile, otherProfile))
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(profiles)
        `when`(accounts.profile(profile.id)).thenAnswer { profiles.value.firstOrNull { it.id == profile.id && it.enabled } }
        `when`(accounts.profile(otherProfile.id)).thenAnswer { profiles.value.firstOrNull { it.id == otherProfile.id && it.enabled } }
        repository = mock(SubsonicRepository::class.java)
        `when`(repository.accounts).thenReturn(accounts)
        `when`(repository.browseKey(profile.id, albumId = album.id)).thenReturn(key)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `cold rebuilt album retains expired persisted songs when the server is offline`() = runTest(dispatcher) {
        val stored = mutableMapOf<ServerBrowseKey, ServerBrowsePage>()
        val store = object : ServerBrowseStore {
            override suspend fun read(key: ServerBrowseKey) = stored[key]
            override suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage) { stored[key] = page }
            override suspend fun retainProfiles(revisions: Map<String, Long>) {
                stored.keys.removeAll { revisions[it.profileId] != it.revision }
            }
            override suspend fun clear() { stored.clear() }
        }
        SubsonicBrowseCache(store, backgroundScope, now = { 1L }).fetch(key) {
            ServerBrowsePage(songs = songs)
        }
        val reopened = SubsonicBrowseCache(store, backgroundScope, now = { 120_001L })
        doReturn(reopened.snapshot(key)).`when`(repository).cached(key)
        doAnswer { throw SubsonicException(-1, "offline", SubsonicFailureKind.NETWORK) }
            .`when`(repository).browse(key, false)
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            assertEquals(songs, vm.state.value.songs)
            assertFalse(vm.state.value.loading)
            assertEquals(CoreCommonR.string.server_error_network, vm.state.value.error)
            assertEquals(album, vm.state.value.album)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `cached album is visible while a successful refresh replaces its songs`() = runTest(dispatcher) {
        `when`(repository.cached(key)).thenReturn(ServerBrowsePage(songs = songs, savedAtMs = 1L))
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).browse(key, false)
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            assertEquals(songs, vm.state.value.songs)
            assertTrue(vm.state.value.loading)
            response.resume(ServerBrowsePage(songs = songs.reversed()))
            runCurrent()
            assertEquals(songs.reversed(), vm.state.value.songs)
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `late cached album cannot replace another server with the same album id`() = runTest(dispatcher) {
        var snapshot: Continuation<ServerBrowsePage?>? = null
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            snapshot = invocation.rawArguments.last() as Continuation<ServerBrowsePage?>
            COROUTINE_SUSPENDED
        }.`when`(repository).cached(key)
        `when`(repository.browse(key, false)).thenReturn(ServerBrowsePage(songs = songs))
        val otherAlbum = album.copy(profileId = otherProfile.id)
        val otherKey = key.copy(profileId = otherProfile.id)
        `when`(repository.browseKey(otherProfile.id, albumId = album.id)).thenReturn(otherKey)
        `when`(repository.cached(otherKey)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
        `when`(repository.browse(otherKey, false)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            vm.start(otherAlbum)
            runCurrent()
            snapshot?.resume(ServerBrowsePage(songs = songs))
            runCurrent()
            assertEquals(otherAlbum, vm.state.value.album)
            assertEquals(songs.reversed(), vm.state.value.songs)
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `revision change rejects a late cached album and keeps the new revision songs`() = runTest(dispatcher) {
        var snapshot: Continuation<ServerBrowsePage?>? = null
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            snapshot = invocation.rawArguments.last() as Continuation<ServerBrowsePage?>
            COROUTINE_SUSPENDED
        }.`when`(repository).cached(key)
        `when`(repository.browse(key, false)).thenReturn(ServerBrowsePage(songs = songs))
        val revised = profile.copy(revision = 1L)
        val revisedKey = key.copy(revision = 1L)
        `when`(repository.cached(revisedKey)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
        `when`(repository.browse(revisedKey, false)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            `when`(repository.browseKey(profile.id, albumId = album.id)).thenReturn(revisedKey)
            profiles.value = listOf(revised, otherProfile)
            runCurrent()
            snapshot?.resume(ServerBrowsePage(songs = songs))
            runCurrent()
            assertEquals(1L, vm.state.value.revision)
            assertEquals(songs.reversed(), vm.state.value.songs)
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `loads complete album in source order and retry refreshes the same reference`() = runTest(dispatcher) {
        `when`(repository.browse(key, false)).thenReturn(ServerBrowsePage(songs = songs))
        `when`(repository.browse(key, true)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            assertEquals(songs, vm.state.value.songs)
            assertFalse(vm.state.value.loading)
            vm.retry()
            runCurrent()
            assertEquals(songs.reversed(), vm.state.value.songs)
            assertEquals(album, vm.state.value.album)
            verify(repository).browse(key, true)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `reopening the same album checks repository freshness again`() = runTest(dispatcher) {
        `when`(repository.browse(key, false)).thenReturn(
            ServerBrowsePage(songs = songs), ServerBrowsePage(songs = songs.reversed())
        )
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            assertEquals(songs, vm.state.value.songs)
            vm.start(album)
            runCurrent()
            assertEquals(songs.reversed(), vm.state.value.songs)
            verify(repository, times(2)).browse(key, false)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `account load failure before start stays retryable instead of loading forever`() = runTest(dispatcher) {
        var fail = true
        val accounts = repository.accounts
        doAnswer {
            if (fail) throw IllegalStateException("unavailable")
            Unit
        }.`when`(accounts).load()
        `when`(repository.browse(key, false)).thenReturn(ServerBrowsePage(songs = songs))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            runCurrent()
            assertEquals(CoreCommonR.string.server_error_load_accounts, vm.state.value.error)
            vm.start(album)
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals(CoreCommonR.string.server_error_load_accounts, vm.state.value.error)
            fail = false
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
            assertEquals(songs, vm.state.value.songs)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `deleting selected profile keeps original reference and clears its tracks`() = runTest(dispatcher) {
        `when`(repository.browse(key, false)).thenReturn(ServerBrowsePage(songs = songs))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            profiles.value = listOf(otherProfile)
            runCurrent()
            assertEquals(album, vm.state.value.album)
            assertTrue(vm.state.value.songs.isEmpty())
            assertEquals(CoreCommonR.string.server_unavailable, vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `album failure is visible and retry loads the original full album`() = runTest(dispatcher) {
        `when`(repository.browse(key, false)).thenThrow(IllegalStateException("unavailable"))
        `when`(repository.browse(key, true)).thenReturn(ServerBrowsePage(songs = songs))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            assertEquals(CoreCommonR.string.server_request_failed, vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.songs.isEmpty())
            vm.retry()
            runCurrent()
            assertEquals(null, vm.state.value.error)
            assertEquals(songs, vm.state.value.songs)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `late response cannot replace another server album with the same album id`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).browse(key, false)
        val otherAlbum = album.copy(profileId = otherProfile.id)
        val otherKey = key.copy(profileId = otherProfile.id)
        `when`(repository.browseKey(otherProfile.id, albumId = album.id)).thenReturn(otherKey)
        `when`(repository.browse(otherKey, false)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            vm.start(otherAlbum)
            runCurrent()
            response.resume(ServerBrowsePage(songs = songs))
            runCurrent()
            assertEquals(otherAlbum, vm.state.value.album)
            assertEquals(songs.reversed(), vm.state.value.songs)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `configuration revision invalidates old album response`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).browse(key, false)
        val revised = profile.copy(revision = 1L)
        val revisedKey = key.copy(revision = 1L)
        val vm = ServerAlbumDetailViewModel(repository)
        try {
            vm.start(album)
            runCurrent()
            `when`(repository.browseKey(profile.id, albumId = album.id)).thenReturn(revisedKey)
            `when`(repository.browse(revisedKey, false)).thenReturn(ServerBrowsePage(songs = songs.reversed()))
            profiles.value = listOf(revised, otherProfile)
            runCurrent()
            response.resume(ServerBrowsePage(songs = songs))
            runCurrent()
            assertEquals(1L, vm.state.value.revision)
            assertEquals(songs.reversed(), vm.state.value.songs)
        } finally { vm.viewModelScope.cancel() }
    }
}
