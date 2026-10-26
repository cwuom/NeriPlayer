package moe.ouom.neriplayer.data.model.playlist

sealed interface PlaylistLoadFailure {
    data object Offline : PlaylistLoadFailure
    data object Network : PlaylistLoadFailure
    data object Unknown : PlaylistLoadFailure
    data object Incomplete : PlaylistLoadFailure
    data class Http(val statusCode: Int) : PlaylistLoadFailure
}
