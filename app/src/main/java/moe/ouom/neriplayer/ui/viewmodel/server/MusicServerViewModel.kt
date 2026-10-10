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
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: Int? = null,
    val accountsLoading: Boolean = false,
    val accountsError: Int? = null,
    val nextOffset: Int = 0,
    val savedAtMs: Long = 0L,
    val loadedOffsets: List<Int> = emptyList(),
    val failedMore: Boolean = false
)

class MusicServerViewModel(
    private val repository: SubsonicRepository = AppContainer.subsonicRepository
) : ViewModel() {
    val accounts = repository.accounts
    val profiles = accounts.profiles
    private val mutableState = MutableStateFlow(MusicServerState(accountsLoading = true))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var selectedRevision: Long? = null
    private var accountsRequest: Job? = null
    private var requestVersion = 0L
    private val locations = LinkedHashMap<String, MusicServerState>(16, .75f, true)
    private val positions = mutableMapOf<String, Pair<Int, Int>>()
    private val roots = LinkedHashMap<String, MusicServerState>(16, .75f, true)
    private var parent: MusicServerState? = null

    val locationKey: String get() = locationKey(state.value)
    private fun locationKey(value: MusicServerState): String =
        ServerBrowseKey(value.profileId ?: "", selectedRevision ?: -1L,
            if (value.album != null) "album" else value.category.browseKind(value.query),
            value.album?.id ?: value.query).cacheKey + if (value.album != null) ":${value.category}" else ""

    fun listPosition(key: String): Pair<Int, Int> = positions[key] ?: (0 to 0)
    fun saveListPosition(key: String, index: Int, offset: Int) {
        positions[key] = index to offset
        if (positions.size > 32) positions.keys.firstOrNull { it !in locations && it != locationKey }?.let(positions::remove)
    }
    fun editQuery(value: String) {
        mutableState.update { it.copy(inputQuery = value, query = if (it.album != null) value.trim() else it.query) }
    }

    init { reloadAccounts() }

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
        if (cached.loadedOffsets.isEmpty() || System.currentTimeMillis() - cached.savedAtMs !in 0 until 60_000L) load()
    }

    private fun rememberLocation() {
        locations[locationKey] = state.value.copy(loading = false)
        while (locations.size > 16) {
            val oldest = locations.keys.first()
            locations.remove(oldest)
            positions.remove(oldest)
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

    fun retry() = load(more = state.value.failedMore, force = true)
    fun refresh() = load(force = true)

    fun load(more: Boolean = false, force: Boolean = false) {
        val snapshot = state.value
        val id = snapshot.profileId ?: return
        if (more && (snapshot.loading || !snapshot.hasMore)) return
        request?.cancel()
        val version = ++requestVersion
        request = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, failedMore = more) }
            val offsets = if (more) listOf(snapshot.nextOffset) else snapshot.loadedOffsets.ifEmpty { listOf(0) }
            var aggregate = if (more) snapshot else snapshot.copy(albums = emptyList(), songs = emptyList(), loadedOffsets = emptyList())
            try {
                // Read all retained pages first, so a background refresh cannot briefly drop pagination.
                if (!more && snapshot.loadedOffsets.isEmpty()) {
                    val key = repository.browseKey(id, snapshot.album?.id,
                        if (snapshot.album != null) "" else snapshot.query, category = snapshot.category)
                    repository.browseCache.snapshot(key)?.let { page ->
                        if (version == requestVersion) mutableState.update { current ->
                            appendPage(aggregate, page, 0).copy(query = current.query,
                                inputQuery = current.inputQuery, loading = true)
                        }
                    }
                }
                for (offset in offsets) {
                    val key = repository.browseKey(id, snapshot.album?.id,
                        if (snapshot.album != null) "" else snapshot.query, offset, category = snapshot.category)
                    val page = repository.browse(key, force)
                    aggregate = appendPage(aggregate, page, offset)
                    if (!page.hasMore) break
                }
                if (version == requestVersion) mutableState.update { current ->
                    aggregate.copy(query = current.query, inputQuery = current.inputQuery,
                        loading = false, error = null, failedMore = false)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == requestVersion) mutableState.update { it.copy(loading = false, error = userError(error), failedMore = more) }
            }
        }
    }

    private fun appendPage(current: MusicServerState, page: ServerBrowsePage, offset: Int) = current.copy(
        albums = (current.albums + page.albums).distinctBy { it.id },
        songs = (current.songs + page.songs).distinctBy { it.audioId },
        hasMore = page.hasMore, nextOffset = offset + if (current.album == null && current.category == ServerLibraryCategory.ALBUMS) page.albums.size else page.songs.size,
        savedAtMs = if (current.loadedOffsets.isEmpty()) page.savedAtMs else minOf(current.savedAtMs, page.savedAtMs),
        loadedOffsets = current.loadedOffsets + offset
    )

    suspend fun add(label: String, url: String, username: String, password: String) =
        repository.addAccount(label, url, username, password)

    companion object {
        fun userError(error: Exception, accountInput: Boolean = false): Int =
            subsonicErrorMessageRes(error, accountInput)
    }
}
