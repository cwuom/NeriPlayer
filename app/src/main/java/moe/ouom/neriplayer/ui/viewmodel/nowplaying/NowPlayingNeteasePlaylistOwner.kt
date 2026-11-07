package moe.ouom.neriplayer.ui.viewmodel.nowplaying

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.concurrent.RequestGeneration
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.common.R as CoreCommonR

internal fun canCollectToNeteasePlaylist(song: SongItem?, audioInfo: PlaybackAudioInfo?): Boolean =
    song != null && !song.isLocalSong() && audioInfo?.source == PlaybackAudioSource.NETEASE &&
        !audioInfo.isNeteaseLocalFallback

internal data class NowPlayingNeteasePlaylistState(
    val song: SongItem? = null,
    val playlists: List<NeteaseRemotePlaylist> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null,
    val submitting: Boolean = false
)

/** 歌单选择可取消；提交后固定歌曲与歌单，不跟随正在播放的歌曲变化。 */
internal class NowPlayingNeteasePlaylistOwner(
    private val scope: CoroutineScope,
    private val hasLogin: () -> Boolean,
    private val loadPlaylists: suspend () -> List<NeteaseRemotePlaylist>,
    private val addSong: suspend (Long, SongItem) -> NeteaseLikeSyncResult,
    private val feedback: (UiText) -> Unit
) {
    private val requests = RequestGeneration()
    private var loadJob: Job? = null
    private val mutableState = MutableStateFlow(NowPlayingNeteasePlaylistState())
    val state = mutableState.asStateFlow()

    fun open(song: SongItem, audioInfo: PlaybackAudioInfo?, offline: Boolean) {
        if (state.value.submitting || !canCollectToNeteasePlaylist(song, audioInfo)) return
        if (!checkAccess(offline)) return
        dismiss()
        val request = requests.capture()
        mutableState.value = NowPlayingNeteasePlaylistState(song = song, loading = true)
        loadJob = scope.launch {
            try {
                val playlists = loadPlaylists()
                if (!request.isCurrent) return@launch
                mutableState.value = state.value.copy(
                    playlists = playlists,
                    loading = false,
                    error = if (playlists.isEmpty()) UiText.Resource(
                        CoreCommonR.string.local_playlist_sync_netease_no_playlists
                    ) else null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request.isCurrent) {
                    mutableState.value = state.value.copy(
                        loading = false,
                        error = UiText.Resource(CoreCommonR.string.local_playlist_sync_netease_load_failed)
                    )
                }
            }
        }
    }

    fun dismiss() {
        requests.advance()
        loadJob?.cancel()
        loadJob = null
        mutableState.value = NowPlayingNeteasePlaylistState(submitting = state.value.submitting)
    }

    fun updatePlayback(song: SongItem?, audioInfo: PlaybackAudioInfo?) {
        val selectedSong = state.value.song ?: return
        if (!canCollectToNeteasePlaylist(song, audioInfo) || !selectedSong.sameIdentityAs(song)) {
            dismiss()
        }
    }

    fun select(
        playlist: NeteaseRemotePlaylist,
        currentSong: SongItem?,
        audioInfo: PlaybackAudioInfo?,
        offline: Boolean
    ): Boolean {
        updatePlayback(currentSong, audioInfo)
        val snapshot = state.value
        val song = snapshot.song ?: return false
        if (snapshot.loading || snapshot.submitting || playlist !in snapshot.playlists) return false
        if (!checkAccess(offline)) return false
        dismiss()
        mutableState.value = state.value.copy(submitting = true)
        feedback(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_adding, listOf(playlist.name)))
        scope.launch {
            try {
                val result = addSong(playlist.id, song)
                feedback(neteasePlaylistCollectionResultText(result, playlist.name))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_failed, listOf(playlist.name)))
            } finally {
                mutableState.value = state.value.copy(submitting = false)
            }
        }
        return true
    }

    private fun checkAccess(offline: Boolean): Boolean {
        val error = when {
            offline -> CoreCommonR.string.nowplaying_netease_playlist_offline
            !hasLogin() -> CoreCommonR.string.nowplaying_netease_playlist_login_required
            else -> return true
        }
        feedback(UiText.Resource(error))
        return false
    }
}

internal fun neteasePlaylistCollectionResultText(result: NeteaseLikeSyncResult, playlistName: String): UiText {
    val message = when {
        result.failed > 0 || result.skippedUnsupported > 0 -> CoreCommonR.string.nowplaying_netease_playlist_failed
        result.added > 0 -> CoreCommonR.string.nowplaying_netease_playlist_added
        result.skippedExisting > 0 -> CoreCommonR.string.nowplaying_netease_playlist_existing
        else -> CoreCommonR.string.nowplaying_netease_playlist_failed
    }
    return UiText.Resource(message, listOf(playlistName))
}
