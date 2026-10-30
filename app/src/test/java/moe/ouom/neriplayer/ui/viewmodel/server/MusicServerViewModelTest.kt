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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseKey
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowsePage
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicBrowseCache
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
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
    private val rootKey = ServerBrowseKey(profile.id, profile.revision, "catalog-albums", size = 500)
    private lateinit var repository: SubsonicRepository
    private val artworkUpdates = MutableStateFlow(0L)
    private var resolveArtwork: (ServerArtist) -> ServerArtist = { it }

    @Before fun setUp() = kotlinx.coroutines.test.runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenReturn(profile)
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
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        ServerLibraryCategory.entries.forEach { category ->
            val key = rootKey.copy(kind = "catalog-${category.name.lowercase()}")
            `when`(repository.catalogKey(profile.id, category)).thenReturn(key)
            `when`(repository.catalog(profile.id, category, false)).thenReturn(page)
        }
    }

    @Test fun `cached song artwork updates visible artists without resetting query or scroll`() = runTest(dispatcher) {
        val artist = ServerArtist(profile.id, "raw-artist", "Artist", null, 2)
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ARTISTS, false)).thenReturn(
            ServerBrowsePage(artists = listOf(artist), savedAtMs = System.currentTimeMillis()))
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.setCategory(ServerLibraryCategory.ARTISTS)
            runCurrent()
            vm.editQuery("draft")
            val location = vm.locationKey
            vm.saveListPosition(location, 4, 29)
            assertEquals(null, vm.state.value.artists.single().imageUrl)
            resolveArtwork = { it.copy(fallbackCoverUrl = "https://example.test/last-song.jpg") }
            artworkUpdates.value++
            runCurrent()
            assertEquals("https://example.test/last-song.jpg", vm.state.value.artists.single().imageUrl)
            assertEquals("draft", vm.state.value.inputQuery)
            assertEquals(location, vm.locationKey)
            assertEquals(4 to 29, vm.listPosition(location))
        } finally { vm.viewModelScope.cancel() }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `saved item anchor follows the same album after loading row disappears`() {
        val album = ServerAlbum(profile.id, "first", "Album", "Artist", null, 1)
        val loading = MusicServerState(profileId = profile.id, loading = true,
            albums = listOf(album, album.copy(id = "second"), album.copy(id = "third")))
        val before = musicServerListItemKeys(loading, false, true, false, emptyList())
        val after = musicServerListItemKeys(loading.copy(loading = false), false, true, false, emptyList())
        val saved = MusicServerListPosition(before.indexOf("album:second"), 27, "album:second")

        assertEquals(saved.index - 1, after.indexOf("album:second"))
        assertEquals(after.indexOf("album:second") to 27, saved.resolve(after))
    }

    @Test fun `saved song anchor survives selection actions being restored`() {
        val state = MusicServerState(profileId = profile.id, category = ServerLibraryCategory.SONGS)
        val selected = musicServerListItemKeys(state, false, true, true, listOf("first", "second"))
        val normal = musicServerListItemKeys(state, false, true, false, listOf("first", "second"))
        val saved = MusicServerListPosition(selected.indexOf("song:second"), 11, "song:second")

        assertEquals(listOf("song:first", "song:second"), selected)
        assertEquals(normal.indexOf("song:second") to 11, saved.resolve(normal))
    }

    @Test fun `selection keeps filtered songs without browser controls or search heading`() {
        val state = MusicServerState(profileId = profile.id, category = ServerLibraryCategory.SONGS,
            query = "night", inputQuery = "night")

        assertEquals(listOf("song:matching"),
            musicServerListItemKeys(state, true, true, true, listOf("matching")))
    }

    @Test fun `selection song anchors account for retained refresh status rows`() {
        val state = MusicServerState(profileId = profile.id, category = ServerLibraryCategory.SONGS,
            loading = true, error = CoreCommonR.string.server_action_failed)
        val selected = musicServerListItemKeys(state, false, true, true, listOf("first", "second"))

        assertEquals(listOf("loading", "error", "song:first", "song:second"), selected)
        assertEquals(3 to 11, MusicServerListPosition(1, 11, "song:second").resolve(selected))
    }

    @Test fun `missing anchors and legacy positions keep their index fallback`() {
        val itemKeys = listOf("profiles", "categories", "search", "album:current")
        assertEquals(7 to 13, MusicServerListPosition(7, 13, "album:removed").resolve(itemKeys))
        assertEquals(2 to 9, MusicServerListPosition(2, 9).resolve(itemKeys))
    }

    @Test fun `embedded and dialog entries keep independent anchors for the same location`() = runTest(dispatcher) {
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenReturn(ServerBrowsePage(savedAtMs = System.currentTimeMillis()))
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            val embedded = musicServerListPositionKey(vm.locationKey, showTitle = false)
            val dialog = musicServerListPositionKey(vm.locationKey, showTitle = true)
            assertNotEquals(embedded, dialog)
            vm.saveListPosition(embedded, 5, 20, "album:embedded")
            vm.saveListPosition(dialog, 8, 35, "album:dialog")

            assertEquals(5 to 20, vm.listPosition(embedded))
            assertEquals(8 to 35, vm.listPosition(dialog))
            assertEquals(2 to 20, vm.listPosition(embedded, listOf("profiles", "album:dialog", "album:embedded")))
            assertEquals(2 to 35, vm.listPosition(dialog, listOf("browser-header", "profiles", "album:dialog", "album:embedded")))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `draft typed during loading survives the response`() = runTest(dispatcher) {
        lateinit var response: Continuation<ServerBrowsePage>
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            response = invocation.rawArguments.last() as Continuation<ServerBrowsePage>
            COROUTINE_SUSPENDED
        }.`when`(repository).catalog(profile.id, ServerLibraryCategory.ALBUMS, false)
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
        }.`when`(repository).catalog(profile.id, ServerLibraryCategory.ALBUMS, false)
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
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenReturn(page)
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
        val album = ServerAlbum(profile.id, "album", "Album", "Artist", null, 1)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenReturn(page.copy(albums = listOf(album)))
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
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `album search and clear stay inside album and back restores parent search`() = runTest(dispatcher) {
        val detailKey = ServerBrowseKey(profile.id, 0, "album", "album")
        val album = ServerAlbum(profile.id, "album", "Album", "Artist", null, 1)
        `when`(repository.browseKey(profile.id, albumId = album.id)).thenReturn(detailKey)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenReturn(page.copy(albums = listOf(album)))
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

    @Test fun `complete catalog is displayed without more and search stays local across three categories`() = runTest(dispatcher) {
        val album = ServerAlbum(profile.id, "album", "Album", "Artist", null, 1)
        val page = ServerBrowsePage(savedAtMs = System.currentTimeMillis())
        val albums = (0 until 1201).map { album.copy(id = "album-$it", name = "Album $it") }
        val artists = listOf(ServerArtist(profile.id, "artist-1", "Artist", null, 5),
            ServerArtist(profile.id, "artist-2", "Artist", null, 3))
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenReturn(page.copy(albums = albums))
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ARTISTS, false)).thenReturn(page.copy(artists = artists))
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            assertEquals(1201, vm.state.value.albums.size)
            assertFalse(vm.state.value.hasMore)
            vm.search("Album 1200")
            runCurrent()
            assertEquals(listOf(albums.last()), vm.state.value.albums)
            vm.search("Artist")
            runCurrent()
            assertEquals(1201, vm.state.value.albums.size)
            vm.setCategory(ServerLibraryCategory.SONGS)
            runCurrent()
            vm.search("")
            runCurrent()
            assertEquals(ServerLibraryCategory.SONGS, vm.state.value.category)
            assertEquals("", vm.state.value.query)
            vm.setCategory(ServerLibraryCategory.ARTISTS)
            runCurrent()
            assertEquals(artists, vm.state.value.artists)
            vm.search("not present")
            runCurrent()
            assertTrue(vm.state.value.artists.isEmpty())
            vm.search("")
            runCurrent()
            assertEquals(artists, vm.state.value.artists)
            assertFalse(musicServerListItemKeys(vm.state.value, false, true, false, emptyList()).contains("more"))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `failed refresh retains complete cached catalog and retry replaces it atomically`() = runTest(dispatcher) {
        val old = ServerAlbum(profile.id, "old", "Old", "Artist", null, 1)
        val page = ServerBrowsePage(albums = listOf(old), savedAtMs = System.currentTimeMillis())
        `when`(repository.cached(rootKey)).thenReturn(page)
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenReturn(page)
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, true)).thenThrow(IllegalStateException("offline"))
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.refresh()
            runCurrent()
            assertEquals(listOf(old), vm.state.value.albums)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.error != null)
            val updated = old.copy(id = "updated", name = "Updated")
            doReturn(page.copy(albums = listOf(updated))).`when`(repository)
                .catalog(profile.id, ServerLibraryCategory.ALBUMS, true)
            vm.retry()
            runCurrent()
            assertEquals(listOf(updated), vm.state.value.albums)
            assertEquals(null, vm.state.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `opening profile warms the other two categories without manual selection`() = runTest(dispatcher) {
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            ServerLibraryCategory.entries.forEach { category ->
                verify(repository, times(1)).catalog(profile.id, category, false)
            }
            assertEquals(ServerLibraryCategory.ALBUMS, vm.state.value.category)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `offline opening displays the saved complete catalog with a retryable error`() = runTest(dispatcher) {
        val old = ServerAlbum(profile.id, "old", "Saved album", "Artist", null, 1)
        val page = ServerBrowsePage(albums = listOf(old), savedAtMs = 1L)
        `when`(repository.cached(rootKey)).thenReturn(page)
        `when`(repository.catalog(profile.id, ServerLibraryCategory.ALBUMS, false)).thenThrow(IllegalStateException("offline"))
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            assertEquals(listOf(old), vm.state.value.albums)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.error != null)
            doReturn(page).`when`(repository).catalog(profile.id, ServerLibraryCategory.ALBUMS, false)
            vm.retry()
            runCurrent()
            verify(repository, times(2)).catalog(profile.id, ServerLibraryCategory.ALBUMS, false)
            assertEquals(null, vm.state.value.error)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `artist anchor uses opaque id so duplicate names stay independent`() {
        val first = ServerArtist(profile.id, "opaque:/+首位", "Same name", null, 2)
        val second = first.copy(id = "opaque:/+次位")
        val state = MusicServerState(profileId = profile.id, category = ServerLibraryCategory.ARTISTS,
            artists = listOf(first, second))
        val keys = musicServerListItemKeys(state, false, true, false, emptyList())
        assertEquals(2, keys.count { it.startsWith("artist:") })
        assertEquals(keys.indexOf("artist:${second.id}") to 17,
            MusicServerListPosition(0, 17, "artist:${second.id}").resolve(keys))
        assertFalse(keys.contains("empty"))
    }

    @Test fun `song search matches title artist and album locally and clearing restores the full catalog`() = runTest(dispatcher) {
        val songs = listOf(
            SongItem(1L, "Night sky", "First", "A", 0L, 1000L, null),
            SongItem(2L, "Track", "Night artist", "B", 0L, 1000L, null),
            SongItem(3L, "Another", "Third", "Night album", 0L, 1000L, null),
            SongItem(4L, "Other", "Fourth", "D", 0L, 1000L, null)
        )
        val page = ServerBrowsePage(songs = songs, savedAtMs = System.currentTimeMillis())
        `when`(repository.catalog(profile.id, ServerLibraryCategory.SONGS, false)).thenReturn(page)
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            vm.setCategory(ServerLibraryCategory.SONGS)
            runCurrent()
            assertEquals(songs, vm.state.value.songs)
            vm.search(" nIgHt ")
            runCurrent()
            assertEquals(songs.take(3), vm.state.value.songs)
            vm.search("")
            runCurrent()
            assertEquals(songs, vm.state.value.songs)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `background category failure does not hide the current catalog and category retry remains usable`() = runTest(dispatcher) {
        `when`(repository.catalog(profile.id, ServerLibraryCategory.SONGS, false)).thenThrow(IllegalStateException("offline"))
        val vm = MusicServerViewModel(repository)
        try {
            runCurrent()
            assertEquals(null, vm.state.value.error)
            vm.setCategory(ServerLibraryCategory.SONGS)
            runCurrent()
            assertTrue(vm.state.value.error != null)
            doReturn(ServerBrowsePage()).`when`(repository).catalog(profile.id, ServerLibraryCategory.SONGS, false)
            vm.retry()
            runCurrent()
            assertEquals(null, vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel() }
    }
}
