package moe.ouom.neriplayer.data.model.youtube.music

data class YouTubeMusicLibraryPlaylist(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val trackCount: Int? = null
)

data class YouTubeMusicPlaylistTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationText: String,
    val durationMs: Long,
    val coverUrl: String,
    val contentLabel: YouTubeContentLabel? = null
)

data class YouTubeMusicPlaylistDetail(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val trackCount: Int? = null,
    val tracks: List<YouTubeMusicPlaylistTrack>,
    val fullyLoaded: Boolean = true,
    val filteredEmpty: Boolean = false
)

data class YouTubeMusicPlayableAudio(
    val url: String,
    val durationMs: Long,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val bitrate: Int = 0
)

data class YouTubeMusicLyrics(
    val lyrics: String,
    val source: String = ""
)

data class YouTubeMusicDebugProbeResult(
    val summary: String,
    val rawJson: String
)

enum class YouTubeMusicSearchResultType {
    Song,
    Video
}

enum class YouTubeMusicSearchFilter {
    Song,
    Video,
    Album,
    FeaturedPlaylist,
    CommunityPlaylist,
    Creator
}

data class YouTubeMusicSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val subtitle: String,
    val coverUrl: String,
    val durationText: String,
    val durationMs: Long,
    val type: YouTubeMusicSearchResultType,
    val contentLabel: YouTubeContentLabel? = null
)

data class YouTubeMusicCreatorSummary(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val channelId: String = ""
)

data class YouTubeMusicCreatorHeader(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val description: String = "",
    val subscriberCountText: String = "",
    val monthlyListenerCountText: String = ""
)

enum class YouTubeMusicCreatorItemType {
    Song,
    Video,
    Album,
    Playlist,
    Creator
}

data class YouTubeMusicCreatorItem(
    val type: YouTubeMusicCreatorItemType,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val videoId: String = "",
    val browseId: String = "",
    val playlistId: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val contentLabel: YouTubeContentLabel? = null
)

data class YouTubeMusicCreatorBrowseEndpoint(
    val browseId: String,
    val params: String = ""
)

data class YouTubeMusicCreatorSection(
    val title: String,
    val items: List<YouTubeMusicCreatorItem>,
    val moreEndpoint: YouTubeMusicCreatorBrowseEndpoint? = null
)

data class YouTubeMusicCreatorDetail(
    val header: YouTubeMusicCreatorHeader,
    val sections: List<YouTubeMusicCreatorSection>
)

data class YouTubeMusicCreatorItemsPage(
    val title: String,
    val items: List<YouTubeMusicCreatorItem>,
    val continuation: String? = null
)

data class YouTubeMusicVideoMetadata(
    val title: String,
    val authorName: String,
    val thumbnailUrl: String
)

data class YouTubeMusicHomeCursor(
    val token: String,
    val locale: YouTubeMusicRequestLocale,
    val visitedTokens: Set<String>,
    val activeShelf: YouTubeMusicHomeShelfContinuation? = null,
    val pendingShelves: List<YouTubeMusicHomeShelfContinuation> = emptyList(),
    val nextShelfIndex: Int = 0
) {
    val requestIdentity: Pair<String?, String> get() = activeShelf?.key to token
}

data class YouTubeMusicHomeShelfContinuation(
    val key: String,
    val title: String,
    val token: String,
    val visitedTokens: Set<String> = setOf(token)
)

data class YouTubeMusicHomePage(
    val shelves: List<YouTubeMusicHomeShelf>,
    val next: YouTubeMusicHomeCursor? = null
)

data class YouTubeMusicHomeShelf(
    val title: String,
    val items: List<YouTubeMusicHomeItem>,
    val continuationKey: String? = null
)

data class YouTubeMusicHomeItem(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val browseId: String = "",
    val videoId: String = "",
    val pageType: String = "",
    val durationText: String = "",
    val durationMs: Long = 0L,
    val artist: String = "",
    val album: String = "",
    val contentLabel: YouTubeContentLabel? = null
)

data class YouTubeMusicHomeSongMetadata(
    val artist: String,
    val album: String
)
