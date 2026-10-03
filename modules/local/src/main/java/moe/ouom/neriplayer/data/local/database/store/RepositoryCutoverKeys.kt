package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore


object RepositoryCutoverKeys {
    const val LOCAL_PLAYLIST = LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
    const val PLAY_HISTORY = PlayHistoryRoomStore.CUTOVER_STATE_METADATA_KEY
    const val PLAYLIST_USAGE = PlaylistUsageRoomStore.CUTOVER_STATE_METADATA_KEY
    const val LOCAL_PLAYLIST_PLAYBACK = LocalPlaylistPlaybackRoomStore.CUTOVER_STATE_METADATA_KEY
    const val PLAYBACK_STATS = PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
    const val FAVORITE_PLAYLIST = FavoritePlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
    const val TRAFFIC_STATS = TrafficStatsRoomStore.CUTOVER_STATE_METADATA_KEY
    const val COVER_URL_MAPPING = CoverUrlMappingRoomStore.CUTOVER_STATE_METADATA_KEY
}
