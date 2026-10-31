package moe.ouom.neriplayer.ui.viewmodel.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.api.subsonicErrorMessageRes
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowsePage
import moe.ouom.neriplayer.platform.subsonic.repository.ServerBrowseKey
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import moe.ouom.neriplayer.common.R as CoreCommonR

data class MusicServerState(
    val profileId: String? = null,
    val category: ServerLibraryCategory = ServerLibraryCategory.ALBUMS,
    val album: ServerAlbum? = null,
    val query: String = "",
    val inputQuery: String = "",
    val albums: List<ServerAlbum> = emptyList(),
    val songs: List<SongItem> = emptyList(),
    val artists: List<ServerArtist> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: Int? = null,
    val accountsLoading: Boolean = false,
    val accountsError: Int? = null,
    val savedAtMs: Long = 0L,
    val loaded: Boolean = false
)

internal fun musicServerListPositionKey(location: String, showTitle: Boolean): String =
    "${if (showTitle) "dialog" else "embedded"}:$location"

internal data class MusicServerListPosition(val index: Int, val offset: Int, val itemKey: String? = null) {
    fun resolve(itemKeys: List<String>): Pair<Int, Int> =
        (itemKey?.let(itemKeys::indexOf)?.takeIf { it >= 0 } ?: index) to offset
}

internal fun musicServerListItemKeys(
    state: MusicServerState,
    showTitle: Boolean,
    hasEnabledProfiles: Boolean,
    selectionMode: Boolean,
    songKeys: List<String>
): List<String> = buildList {
    if (!selectionMode) {
        if (showTitle) add("browser-header")
        add("profiles")
    }
    val accountMessage = when {
        state.accountsLoading -> "accounts-loading"
        state.accountsError != null -> "accounts-error"
        !hasEnabledProfiles -> "no-servers"
        else -> null
    }
    if (accountMessage != null) {
        add(accountMessage)
        return@buildList
    }
    if (!selectionMode) addAll(listOf("categories", "search"))
    if (state.loading) add("loading")
    if (state.error != null) add("error")
    if (!selectionMode && state.query.isNotBlank()) add("search-title")
    when (state.category) {
        ServerLibraryCategory.SONGS -> {
            if (!selectionMode) add("song-actions")
            songKeys.forEach { add("song:$it") }
        }
        ServerLibraryCategory.ALBUMS -> state.albums.forEach { add("album:${it.id}") }
        ServerLibraryCategory.ARTISTS -> state.artists.forEach { add("artist:${it.id}") }
    }
    val empty = when (state.category) {
        ServerLibraryCategory.ALBUMS -> state.albums.isEmpty()
        ServerLibraryCategory.SONGS -> songKeys.isEmpty()
        ServerLibraryCategory.ARTISTS -> state.artists.isEmpty()
    }
    if (!state.loading && state.error == null && empty) add("empty")
}

class MusicServerViewModel(
    private val repository: SubsonicRepository = AppContainer.subsonicRepository
) : ViewModel() {
    val accounts = repository.accounts
    val profiles = accounts.profiles
    private val mutableState = MutableStateFlow(MusicServerState(accountsLoading = true))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var warming: Job? = null
    private var selectedRevision: Long? = null
    private var accountsRequest: Job? = null
    private var requestVersion = 0L
    private var retryForce = false
    private val locations = LinkedHashMap<String, MusicServerState>(16, .75f, true)
    private val positions = mutableMapOf<String, MusicServerListPosition>()
    private val roots = LinkedHashMap<String, MusicServerState>(16, .75f, true)
    private var parent: MusicServerState? = null

    val locationKey: String get() = locationKey(state.value)
    private fun locationKey(value: MusicServerState): String =
        ServerBrowseKey(value.profileId ?: "", selectedRevision ?: -1L,
            if (value.album != null) "album" else value.category.browseKind(value.query),
            value.album?.id ?: value.query).cacheKey + if (value.album != null) ":${value.category}" else ""

    fun listPosition(key: String): Pair<Int, Int> = positions[key]?.let { it.index to it.offset } ?: (0 to 0)
    fun listPosition(key: String, itemKeys: List<String>): Pair<Int, Int> =
        positions[key]?.resolve(itemKeys) ?: (0 to 0)

    fun saveListPosition(key: String, index: Int, offset: Int, itemKey: String? = null) {
        positions[key] = MusicServerListPosition(index, offset, itemKey)
        if (positions.size > 32) positions.keys.firstOrNull { it != key && it !in locations && it != locationKey }?.let(positions::remove)
    }
    fun editQuery(value: String) {
        mutableState.update { it.copy(inputQuery = value, query = if (it.album != null) value.trim() else it.query) }
    }

    init {
        reloadAccounts()
        viewModelScope.launch {
            repository.browseCache.updates.collect { updateArtistArtwork() }
        }
    }

    private suspend fun updateArtistArtwork() {
        val snapshot = state.value
        if (snapshot.category != ServerLibraryCategory.ARTISTS || snapshot.artists.isEmpty()) return
        val revision = selectedRevision
        val artists = try { repository.cachedArtists(snapshot.profileId ?: return, snapshot.artists) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        mutableState.update { current ->
            if (revision == selectedRevision && current.profileId == snapshot.profileId &&
                current.category == snapshot.category && current.artists == snapshot.artists) {
                current.copy(artists = artists)
            } else current
        }
    }

    fun reloadAccounts() {
        accountsRequest?.cancel()
        accountsRequest = viewModelScope.launch {
            mutableState.update { it.copy(accountsLoading = true, accountsError = null) }
            try { accounts.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(accountsLoading = false, accountsError = CoreCommonR.string.server_error_load_accounts) }
                return@launch
            }
            mutableState.update { it.copy(accountsLoading = false) }
            profiles.collect { list ->
                val selected = list.firstOrNull { it.id == state.value.profileId && it.enabled }
                if (selected == null) select(list.firstOrNull { it.enabled }?.id)
                else if (selectedRevision != selected.revision) {
                    request?.cancel()
                    locations.clear()
                    positions.clear()
                    roots.clear()
                    parent = null
                    selectedRevision = selected.revision
                    navigate(MusicServerState(profileId = selected.id), saveCurrent = false)
                    warmCatalogs(selected.id)
                }
            }
        }
    }

    private fun navigate(destination: MusicServerState, saveCurrent: Boolean = true, resetInput: Boolean = false) {
        if (saveCurrent && state.value.profileId != null) {
            rememberLocation()
        }
        request?.cancel()
        requestVersion++
        val restored = locations[locationKey(destination)] ?: destination
        mutableState.value = if (resetInput) restored.copy(inputQuery = destination.inputQuery,
            query = if (destination.album != null) destination.query else restored.query) else restored
        val cached = state.value
        if (cached.album == null || !cached.loaded ||
            System.currentTimeMillis() - cached.savedAtMs !in 0 until 60_000L) load()
    }

    private fun rememberLocation() {
        locations[locationKey] = state.value.copy(loading = false)
        while (locations.size > 16) {
            val oldest = locations.keys.first()
            locations.remove(oldest)
            positions.remove(oldest)
            positions.remove(musicServerListPositionKey(oldest, showTitle = false))
            positions.remove(musicServerListPositionKey(oldest, showTitle = true))
        }
    }

    fun select(id: String?) {
        if (id == state.value.profileId && selectedRevision == id?.let { accounts.profile(it)?.revision }) return
        // Save with the old revision before switching server.
        rememberLocation()
        (if (state.value.album == null) state.value else parent)?.let { root ->
            root.profileId?.let { roots["$it:$selectedRevision"] = root.copy(loading = false) }
        }
        while (roots.size > 16) roots.remove(roots.keys.first())
        selectedRevision = id?.let { accounts.profile(it)?.revision }
        parent = null
        navigate(roots["$id:$selectedRevision"] ?: MusicServerState(profileId = id), saveCurrent = false)
        warmCatalogs(id)
    }

    private fun warmCatalogs(id: String?) {
        warming?.cancel()
        if (id == null) return
        val selectedCategory = state.value.category
        warming = viewModelScope.launch {
            for (category in ServerLibraryCategory.entries.filter { it != selectedCategory }) {
                try { repository.catalog(id, category) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 打开对应分类时仍可重试 */ }
            }
        }
    }

    fun search(query: String) {
        val normalized = query.trim()
        // Album detail searches only its fully loaded tracks; they never leave the album.
        if (state.value.album != null || state.value.query == normalized) {
            mutableState.update { it.copy(query = normalized, inputQuery = normalized) }
            return
        }
        parent = null
        navigate(MusicServerState(profileId = state.value.profileId, category = state.value.category,
            query = normalized, inputQuery = normalized), resetInput = true)
    }

    fun setCategory(category: ServerLibraryCategory) {
        val current = state.value
        if (current.category == category && current.album == null) return
        val root = if (current.album != null) parent ?: current.copy(query = "", inputQuery = "") else current
        parent = null
        navigate(MusicServerState(profileId = current.profileId, category = category,
            query = root.query, inputQuery = root.inputQuery), resetInput = true)
    }

    fun open(album: ServerAlbum) {
        parent = state.value.copy(loading = false)
        navigate(MusicServerState(profileId = album.profileId, category = state.value.category, album = album), resetInput = true)
    }

    fun back() {
        val destination = if (state.value.album != null) parent ?: MusicServerState(profileId = state.value.profileId, category = state.value.category)
            else MusicServerState(profileId = state.value.profileId, category = state.value.category)
        parent = null
        navigate(destination)
    }

    fun retry() = load(force = retryForce)
    fun refresh() = load(force = true)

    private fun load(force: Boolean = false) {
        val snapshot = state.value
        val id = snapshot.profileId ?: return
        request?.cancel()
        retryForce = force
        val version = ++requestVersion
        request = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val key = if (snapshot.album != null) repository.browseKey(id, albumId = snapshot.album.id)
                    else repository.catalogKey(id, snapshot.category)
                // 先展示完整旧快照，后台同步失败时仍可浏览
                repository.cached(key)?.let { cached ->
                    val page = withArtistArtwork(cached)
                    if (version == requestVersion) mutableState.update { current ->
                        applyPage(current, page).copy(loading = true)
                    }
                }
                val page = withArtistArtwork(if (snapshot.album != null) repository.browse(key, force)
                    else repository.catalog(id, snapshot.category, force))
                if (version == requestVersion) mutableState.update { current ->
                    applyPage(current, page).copy(loading = false, error = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == requestVersion) mutableState.update { it.copy(loading = false, error = userError(error)) }
            }
        }
    }

    private suspend fun withArtistArtwork(page: ServerBrowsePage): ServerBrowsePage =
        if (page.artists.isEmpty()) page else page.copy(artists = repository.cachedArtists(
            page.artists.first().profileId, page.artists))

    private fun applyPage(current: MusicServerState, page: ServerBrowsePage): MusicServerState {
        val query = current.query
        fun matches(vararg fields: String) = query.isBlank() || fields.any { it.contains(query, ignoreCase = true) }
        return current.copy(
            albums = page.albums.filter { matches(it.name, it.artist) },
            songs = page.songs.filter { current.album != null || matches(it.name, it.artist, it.album) },
            artists = page.artists.filter { matches(it.name) },
            hasMore = false, savedAtMs = page.savedAtMs, loaded = true
        )
    }

    suspend fun add(label: String, url: String, username: String, password: String) =
        repository.addAccount(label, url, username, password)

    companion object {
        fun userError(error: Exception, accountInput: Boolean = false): Int =
            subsonicErrorMessageRes(error, accountInput)
    }
}
