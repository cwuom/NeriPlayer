package moe.ouom.neriplayer.ui.viewmodel.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.api.subsonicErrorMessageRes
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import moe.ouom.neriplayer.common.R as CoreCommonR

data class ServerAlbumDetailState(
    val album: ServerAlbum? = null,
    val revision: Long? = null,
    val songs: List<SongItem> = emptyList(),
    val loading: Boolean = false,
    val error: Int? = null
)

class ServerAlbumDetailViewModel(
    private val repository: SubsonicRepository = AppContainer.subsonicRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerAlbumDetailState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var accountsRequest: Job? = null
    private var version = 0L
    private var accountsReady = false

    init { loadAccounts() }

    fun start(album: ServerAlbum) {
        val current = state.value.album
        val sameAlbum = current?.profileId == album.profileId && current.id == album.id
        if (sameAlbum) {
            mutableState.value = state.value.copy(album = album)
        } else {
            request?.cancel()
            version++
            mutableState.value = ServerAlbumDetailState(album = album, loading = true)
        }
        if (accountsReady) {
            if (!sameAlbum || request?.isActive != true) load(force = false)
        } else if (accountsRequest?.isActive != true) {
            loadAccounts()
        }
    }

    fun retry() {
        if (accountsReady) load(force = true) else loadAccounts()
    }

    private fun loadAccounts() {
        accountsRequest?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        accountsRequest = viewModelScope.launch {
            try {
                repository.accounts.load()
                accountsReady = true
                repository.accounts.profiles.collect { reconcileProfile() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    loading = false, error = CoreCommonR.string.server_error_load_accounts
                )
            }
        }
    }

    private fun reconcileProfile() {
        val snapshot = state.value
        val album = snapshot.album ?: return
        val profile = repository.accounts.profile(album.profileId)
        if (profile == null) {
            request?.cancel()
            version++
            mutableState.value = snapshot.copy(
                revision = null, songs = emptyList(), loading = false,
                error = CoreCommonR.string.server_unavailable
            )
        } else if (snapshot.revision != profile.revision) {
            request?.cancel()
            version++
            mutableState.value = snapshot.copy(
                revision = profile.revision, songs = emptyList(), loading = true, error = null
            )
            load(force = false)
        }
    }

    private fun load(force: Boolean) {
        val album = state.value.album ?: return
        val revision = repository.accounts.profile(album.profileId)?.revision
        if (revision == null) {
            reconcileProfile()
            return
        }
        request?.cancel()
        val requestVersion = ++version
        mutableState.value = state.value.copy(revision = revision, loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val key = repository.browseKey(album.profileId, albumId = album.id)
                // 过期快照仍可离线浏览，等待服务器响应期间保留已有曲目
                val cached = repository.cached(key)
                if (!isCurrent(requestVersion, album, revision)) return@launch
                if (cached != null) mutableState.value = state.value.copy(songs = cached.songs)
                val page = repository.browse(key, force)
                if (!isCurrent(requestVersion, album, revision)) return@launch
                mutableState.value = state.value.copy(songs = page.songs, loading = false, error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(requestVersion, album, revision)) return@launch
                mutableState.value = state.value.copy(loading = false, error = subsonicErrorMessageRes(error))
            }
        }
    }

    private fun isCurrent(requestVersion: Long, album: ServerAlbum, revision: Long): Boolean =
        requestVersion == version && state.value.album?.profileId == album.profileId &&
            state.value.album?.id == album.id &&
            repository.accounts.profile(album.profileId)?.revision == revision
}
