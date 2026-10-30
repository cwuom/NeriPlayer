package moe.ouom.neriplayer.ui.viewmodel.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.subsonic.api.subsonicErrorMessageRes
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import moe.ouom.neriplayer.common.R as CoreCommonR

data class ServerArtistDetailState(
    val artist: ServerArtist? = null,
    val revision: Long? = null,
    val albums: List<ServerAlbum> = emptyList(),
    val loading: Boolean = false,
    val error: Int? = null
)

class ServerArtistDetailViewModel(
    private val repository: SubsonicRepository = AppContainer.subsonicRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerArtistDetailState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var accountsRequest: Job? = null
    private var version = 0L
    private var accountsReady = false

    init { loadAccounts() }

    suspend fun observeArtwork() {
        repository.browseCache.updates.collect { updateArtwork() }
    }

    private suspend fun updateArtwork() {
        val snapshot = state.value
        val artist = snapshot.artist ?: return
        val revision = snapshot.revision ?: return
        val updated = try { repository.cachedArtist(artist) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        if (state.value.artist == artist && state.value.revision == revision &&
            repository.accounts.profile(artist.profileId)?.revision == revision) {
            mutableState.value = state.value.copy(artist = updated)
        }
    }

    fun start(artist: ServerArtist) {
        val current = state.value.artist
        val sameArtist = current?.profileId == artist.profileId && current.id == artist.id
        if (sameArtist) {
            mutableState.value = state.value.copy(artist = artist)
        } else {
            request?.cancel()
            version++
            mutableState.value = ServerArtistDetailState(artist = artist, loading = true)
        }
        if (accountsReady) {
            if (!sameArtist || request?.isActive != true) load(force = false)
        } else if (accountsRequest?.isActive != true) loadAccounts()
    }

    fun retry() {
        if (accountsReady) load(force = true) else loadAccounts()
    }

    fun refresh() = retry()

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
        val artist = snapshot.artist ?: return
        val profile = repository.accounts.profile(artist.profileId)
        if (profile == null) {
            request?.cancel()
            version++
            mutableState.value = snapshot.copy(
                revision = null, albums = emptyList(), loading = false,
                error = CoreCommonR.string.server_unavailable
            )
        } else if (snapshot.revision != profile.revision) {
            request?.cancel()
            version++
            mutableState.value = snapshot.copy(
                artist = artist.copy(fallbackCoverUrl = null),
                revision = profile.revision, albums = emptyList(), loading = true, error = null
            )
            load(force = false)
        }
    }

    private fun load(force: Boolean) {
        val artist = state.value.artist ?: return
        val revision = repository.accounts.profile(artist.profileId)?.revision
        if (revision == null) {
            reconcileProfile()
            return
        }
        request?.cancel()
        val requestVersion = ++version
        mutableState.value = state.value.copy(revision = revision, loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val key = repository.artistAlbumsKey(artist.profileId, artist.id)
                val initialArtist = repository.cachedArtist(artist)
                if (!isCurrent(requestVersion, artist, revision)) return@launch
                mutableState.value = state.value.copy(artist = initialArtist)
                repository.browseCache.snapshot(key)?.let { cached ->
                    val updated = repository.cachedArtist(artist)
                    if (isCurrent(requestVersion, artist, revision)) {
                        mutableState.value = state.value.copy(artist = updated, albums = cached.albums)
                    }
                }
                val page = repository.artistAlbums(artist.profileId, artist.id, force)
                val updated = repository.cachedArtist(page.artists.singleOrNull() ?: artist)
                if (!isCurrent(requestVersion, artist, revision)) return@launch
                mutableState.value = state.value.copy(artist = updated, albums = page.albums, loading = false, error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(requestVersion, artist, revision)) return@launch
                mutableState.value = state.value.copy(loading = false, error = subsonicErrorMessageRes(error))
            }
        }
    }

    private fun isCurrent(requestVersion: Long, artist: ServerArtist, revision: Long): Boolean =
        requestVersion == version && state.value.artist?.profileId == artist.profileId &&
            state.value.artist?.id == artist.id &&
            repository.accounts.profile(artist.profileId)?.revision == revision
}
