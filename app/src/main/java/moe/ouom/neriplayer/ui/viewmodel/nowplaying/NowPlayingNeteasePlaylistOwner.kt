package moe.ouom.neriplayer.ui.viewmodel.nowplaying

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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

/** 保留最新反馈；页面重建打断展示时，由下一个播放页继续展示。 */
internal class NeteaseCollectionFeedback {
    private val pending = MutableStateFlow<UiText?>(null)

    fun show(message: UiText) {
        pending.value = message
    }

    suspend fun display(showMessage: suspend (UiText) -> Unit) {
        pending.collectLatest { message ->
            if (message != null) {
                showMessage(message)
                pending.compareAndSet(message, null)
            }
        }
    }
}

/** 仅允许收藏正在使用有效网易云音频 ID 的曲目。 */
internal fun canCollectToNeteasePlaylist(song: SongItem?, audioInfo: PlaybackAudioInfo?): Boolean =
    song != null && song.id > 0 && !song.isLocalSong() && audioInfo?.source == PlaybackAudioSource.NETEASE &&
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

    /** 打开歌单选择器，只接收当前请求的查询结果。 */
    fun open(song: SongItem, audioInfo: PlaybackAudioInfo?, offline: Boolean) {
        if (state.value.submitting || !canCollectToNeteasePlaylist(song, audioInfo)) return
        accessError(offline)?.let { feedback(it); return }
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

    /** 取消选择和查询，已提交的写入继续执行。 */
    fun dismiss() {
        requests.advance()
        loadJob?.cancel()
        loadJob = null
        mutableState.value = NowPlayingNeteasePlaylistState(submitting = state.value.submitting)
    }

    /** 提交前切换曲目或音源时，使之前的选择失效。 */
    fun updatePlayback(song: SongItem?, audioInfo: PlaybackAudioInfo?) {
        val selectedSong = state.value.song ?: return
        if (!canCollectToNeteasePlaylist(song, audioInfo) || !selectedSong.sameIdentityAs(song)) {
            dismiss()
        }
    }

    /** 提交成功入队时返回 true，供菜单决定是否收起。 */
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
        accessError(offline)?.let {
            mutableState.value = snapshot.copy(error = it)
            return false
        }
        dismiss()
        mutableState.value = state.value.copy(submitting = true)
        feedback(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_adding, listOf(playlist.name)))
        scope.launch {
            try {
                // 网易云播放请求使用 song.id。明确标记提交副本的来源，避免同步逻辑回退到歌词匹配 ID。
                val track = song.copy(channelId = "netease", audioId = null)
                val result = addSong(playlist.id, track)
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

    /** 同一访问检查分别在打开选择器与提交时执行。 */
    private fun accessError(offline: Boolean): UiText? = when {
        offline -> UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_offline)
        !hasLogin() -> UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_login_required)
        else -> null
    }
}

/** 将单曲同步结果转换为包含目标歌单名的播放页提示。 */
internal fun neteasePlaylistCollectionResultText(result: NeteaseLikeSyncResult, playlistName: String): UiText {
    val message = when {
        result.failed > 0 || result.skippedUnsupported > 0 -> CoreCommonR.string.nowplaying_netease_playlist_failed
        result.added > 0 -> CoreCommonR.string.nowplaying_netease_playlist_added
        result.skippedExisting > 0 -> CoreCommonR.string.nowplaying_netease_playlist_existing
        else -> CoreCommonR.string.nowplaying_netease_playlist_failed
    }
    return UiText.Resource(message, listOf(playlistName))
}
