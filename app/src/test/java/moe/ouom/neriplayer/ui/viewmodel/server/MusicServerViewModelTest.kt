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
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseKey
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowsePage
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseCache
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicServerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = SubsonicProfile("test-profile", "Test server", "https://example.test/", "user")
    private val rootKey = ServerBrowseKey(profile.id, profile.revision, "albums")
    private lateinit var repository: SubsonicRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenReturn(profile)
        repository = mock(SubsonicRepository::class.java)
        `when`(repository.accounts).thenReturn(accounts)
        `when`(repository.browseCache).thenReturn(mock(SubsonicBrowseCache::class.java))
        `when`(repository.browseKey(profile.id)).thenReturn(rootKey)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `draft typed during loading survives the response`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).browse(rootKey, false)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            assertTrue(vm.state.value.loading)
            vm.editQuery("New draft")
            response.resume(ServerBrowsePage(savedAtMs = System.currentTimeMillis()))
            runCurrent()
            assertEquals("New draft", vm.state.value.inputQuery)
            assertEquals("", vm.state.value.query)
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `clear at root resets unsubmitted input and stays cleared after loading`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).browse(rootKey, false)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.editQuery("Unsubmitted text")
            vm.search("")
            assertEquals("", vm.state.value.inputQuery)
            response.resume(ServerBrowsePage(savedAtMs = System.currentTimeMillis()))
            runCurrent()
            assertEquals("", vm.state.value.inputQuery)
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `searching a cached query replaces its saved unsubmitted draft`() = runTest(dispatcher) {
        val searchKey = ServerBrowseKey(profile.id, profile.revision, "search-albums", "Album")
        `when`(repository.browseKey(profile.id, null, "Album")).thenReturn(searchKey)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.browse(rootKey, false)).thenReturn(page)
        `when`(repository.browse(searchKey, false)).thenReturn(page)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.search("Album")
            runCurrent()
            vm.editQuery("Old unsent draft")
            vm.search("")
            runCurrent()
            vm.search(" Album ")
            runCurrent()
            assertEquals("Album", vm.state.value.query)
            assertEquals("Album", vm.state.value.inputQuery)
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `switching category searches the same artist but keeps separate pages and positions`() = runTest(dispatcher) {
        val albumKey = ServerBrowseKey(profile.id, 0, "search-albums", "Artist")
        val songKey = ServerBrowseKey(profile.id, 0, "search", "Artist")
        `when`(repository.browseKey(profile.id, query = "Artist")).thenReturn(albumKey)
        `when`(repository.browseKey(profile.id, query = "Artist", category = ServerLibraryCategory.SONGS)).thenReturn(songKey)
        val album = ServerAlbum(profile.id, "album", "Album", "Artist", null, 1)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.browse(rootKey, false)).thenReturn(page)
        `when`(repository.browse(albumKey, false)).thenReturn(page.copy(albums = listOf(album)))
        `when`(repository.browse(songKey, false)).thenReturn(page)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.search("Artist")
            runCurrent()
            val albumLocation = vm.locationKey
            vm.saveListPosition(albumLocation, 5, 20)
            vm.setCategory(ServerLibraryCategory.SONGS)
            runCurrent()
            assertEquals("Artist", vm.state.value.query)
            assertEquals(ServerLibraryCategory.SONGS, vm.state.value.category)
            assertTrue(vm.state.value.albums.isEmpty())
            assertEquals(0 to 0, vm.listPosition(vm.locationKey))
            vm.setCategory(ServerLibraryCategory.ALBUMS)
            runCurrent()
            assertEquals(listOf(album), vm.state.value.albums)
            assertEquals(albumLocation, vm.locationKey)
            assertEquals(5 to 20, vm.listPosition(vm.locationKey))
            verify(repository, times(1)).browse(albumKey, false)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `album search and clear stay inside album and back restores parent search`() = runTest(dispatcher) {
        val searchKey = ServerBrowseKey(profile.id, 0, "search-albums", "Artist")
        val detailKey = ServerBrowseKey(profile.id, 0, "album", "album")
        val album = ServerAlbum(profile.id, "album", "Album", "Artist", null, 1)
        `when`(repository.browseKey(profile.id, query = "Artist")).thenReturn(searchKey)
        `when`(repository.browseKey(profile.id, albumId = album.id)).thenReturn(detailKey)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.browse(rootKey, false)).thenReturn(page)
        `when`(repository.browse(searchKey, false)).thenReturn(page.copy(albums = listOf(album)))
        `when`(repository.browse(detailKey, false)).thenReturn(page)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.search("Artist")
            runCurrent()
            val rootLocation = vm.locationKey
            vm.saveListPosition(rootLocation, 3, 12)
            vm.open(album)
            runCurrent()
            assertEquals("", vm.state.value.inputQuery)
            vm.editQuery("Track")
            assertEquals("Track", vm.state.value.query)
            vm.search("")
            assertEquals(album, vm.state.value.album)
            assertEquals("", vm.state.value.query)
            vm.back()
            assertEquals("Artist", vm.state.value.query)
            assertEquals("Artist", vm.state.value.inputQuery)
            assertEquals(3 to 12, vm.listPosition(vm.locationKey))
            verify(repository, times(1)).browse(detailKey, false)
            vm.open(album)
            vm.editQuery("Track")
            vm.back()
            vm.open(album)
            assertEquals("", vm.state.value.query)
            assertEquals("", vm.state.value.inputQuery)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `album pagination advances by albums and clearing songs keeps songs category`() = runTest(dispatcher) {
        val searchKey = ServerBrowseKey(profile.id, 0, "search-albums", "Artist")
        val nextKey = searchKey.copy(offset = 2)
        val songsKey = ServerBrowseKey(profile.id, 0, "search", "Artist")
        val allSongsKey = ServerBrowseKey(profile.id, 0, "songs")
        val album = ServerAlbum(profile.id, "album", "Album", "Artist", null, 1)
        `when`(repository.browseKey(profile.id, query = "Artist")).thenReturn(searchKey)
        `when`(repository.browseKey(profile.id, query = "Artist", offset = 2)).thenReturn(nextKey)
        `when`(repository.browseKey(profile.id, query = "Artist", category = ServerLibraryCategory.SONGS)).thenReturn(songsKey)
        `when`(repository.browseKey(profile.id, category = ServerLibraryCategory.SONGS)).thenReturn(allSongsKey)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.browse(rootKey, false)).thenReturn(page)
        `when`(repository.browse(searchKey, false)).thenReturn(page.copy(albums = listOf(album, album.copy(id = "second")), hasMore = true))
        `when`(repository.browse(nextKey, false)).thenReturn(page.copy(albums = listOf(album.copy(id = "third"))))
        `when`(repository.browse(songsKey, false)).thenReturn(page)
        `when`(repository.browse(allSongsKey, false)).thenReturn(page)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.search("Artist")
            runCurrent()
            vm.load(more = true)
            runCurrent()
            assertEquals(3, vm.state.value.albums.size)
            verify(repository).browse(nextKey, false)
            vm.setCategory(ServerLibraryCategory.SONGS)
            runCurrent()
            vm.search("")
            runCurrent()
            assertEquals(ServerLibraryCategory.SONGS, vm.state.value.category)
            assertEquals("", vm.state.value.query)
            verify(repository).browse(allSongsKey, false)
        } finally { vm.viewModelScope.cancel() }
    }
}
