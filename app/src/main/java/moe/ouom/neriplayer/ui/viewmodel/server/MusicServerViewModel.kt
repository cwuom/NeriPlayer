package moe.ouom.neriplayer.ui.viewmodel.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.subsonicErrorMessageRes
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum

data class MusicServerState(
    val profileId: String? = null,
    val album: ServerAlbum? = null,
    val query: String = "",
    val albums: List<ServerAlbum> = emptyList(),
    val songs: List<SongItem> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: Int? = null,
    val accountsLoading: Boolean = false,
    val accountsError: Int? = null
)

class MusicServerViewModel : ViewModel() {
    private val repository = AppContainer.subsonicRepository
    val accounts = repository.accounts
    val profiles = accounts.profiles
    private val mutableState = MutableStateFlow(MusicServerState(accountsLoading = true))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var offset = 0
    private var selectedRevision: Long? = null
    private var accountsRequest: Job? = null

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
                val selected = list.firstOrNull { it.id == state.value.profileId }
                if (selected == null) select(list.firstOrNull()?.id)
                else if (selectedRevision != selected.revision) select(selected.id)
            }
        }
    }

    fun select(id: String?) {
        selectedRevision = id?.let { accounts.profile(it)?.revision }
        request?.cancel()
        mutableState.value = MusicServerState(profileId = id)
        load()
    }

    fun search(query: String) {
        request?.cancel()
        mutableState.update { MusicServerState(profileId = it.profileId, query = query.trim()) }
        load()
    }

    fun open(album: ServerAlbum) {
        request?.cancel()
        mutableState.value = MusicServerState(profileId = album.profileId, album = album)
        load()
    }

    fun load(more: Boolean = false) {
        val snapshot = state.value
        val id = snapshot.profileId ?: return
        if (more && (snapshot.loading || !snapshot.hasMore)) return
        request?.cancel()
        if (!more) offset = 0
        request = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                when {
                    snapshot.album != null -> {
                        val songs = repository.albumSongs(id, snapshot.album.id)
                        mutableState.update { it.copy(songs = songs, hasMore = false) }
                    }
                    snapshot.query.isNotBlank() -> {
                        val songs = repository.search(id, snapshot.query, offset)
                        offset += songs.size
                        mutableState.update { it.copy(songs = if (more) (it.songs + songs).distinctBy { s -> s.audioId } else songs,
                            hasMore = songs.size == 30) }
                    }
                    else -> {
                        val albums = repository.albums(id, offset)
                        offset += albums.size
                        mutableState.update { it.copy(albums = if (more) (it.albums + albums).distinctBy { a -> a.id } else albums,
                            hasMore = albums.size == 30) }
                    }
                }
                mutableState.update { it.copy(loading = false) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                mutableState.update { it.copy(loading = false, error = userError(error)) }
            }
        }
    }

    suspend fun add(label: String, url: String, username: String, password: String) =
        repository.addAccount(label, url, username, password)

    companion object {
        fun userError(error: Exception, accountInput: Boolean = false): Int =
            subsonicErrorMessageRes(error, accountInput)
    }
}
