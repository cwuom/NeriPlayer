package moe.ouom.neriplayer.platform.youtube.api.parser

import java.util.Locale
import moe.ouom.neriplayer.platform.youtube.api.bootstrap.YouTubeBootstrapHtmlSource
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemsPage
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeSongMetadata
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLibraryPlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLyrics
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import moe.ouom.neriplayer.data.model.youtube.music.ParsedYouTubeMusicHomeShelf
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicBootstrapConfig
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlaylistPage
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject?.objectPath(vararg keys: String): JSONObject? =
    keys.fold(this) { current, key -> current?.optJSONObject(key) }

private val SEARCH_STAT_TOKENS = listOf(
    "播放", "观看", "views", "view", "listeners", "listener", "monthly", "观众", "订阅者", "subscriber"
)

private data class CreatorBrowseTypeRule(
    val pageTypeToken: String,
    val browseIdPrefix: String,
    val itemType: YouTubeMusicCreatorItemType
)

private val CREATOR_BROWSE_TYPE_RULES = listOf(
    CreatorBrowseTypeRule("ARTIST", "UC", YouTubeMusicCreatorItemType.Creator),
    CreatorBrowseTypeRule("ALBUM", "MPRE", YouTubeMusicCreatorItemType.Album),
    CreatorBrowseTypeRule("PLAYLIST", "VL", YouTubeMusicCreatorItemType.Playlist)
)

private val CREATOR_SEARCH_TEXT_KEYS = listOf("title", "subtitle")

private data class ParsedYouTubeMusicSearchMetadata(
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationText: String = ""
)

internal data class YouTubeMusicFollowedArtistsPage(
    val artists: List<YouTubeMusicCreatorSummary>,
    val continuation: String?
)

object YouTubeMusicParser {
    internal fun parseBootstrapConfig(
        html: String,
        cookieHeader: String,
        userAgent: String
    ): YouTubeMusicBootstrapConfig {
        val now = System.currentTimeMillis()
        val bootstrapSource = YouTubeBootstrapHtmlSource(html)
        val dataSyncId = bootstrapSource.optionalString("DATASYNC_ID", "datasyncId")
        val (_, derivedUserSessionId) = parseDataSyncId(dataSyncId)
        val loggedIn = bootstrapSource.optionalBoolean("LOGGED_IN")
            .equals("true", ignoreCase = true)
        return YouTubeMusicBootstrapConfig(
            apiKey = bootstrapSource.requireString(
                "YouTube Music bootstrap parse failed",
                "INNERTUBE_API_KEY",
                "innertubeApiKey"
            ),
            webRemixClientVersion = bootstrapSource.requireString(
                "YouTube Music bootstrap parse failed",
                "INNERTUBE_CLIENT_VERSION",
                "INNERTUBE_CONTEXT_CLIENT_VERSION",
                "innertubeContextClientVersion"
            ),
            visitorData = bootstrapSource.requireString(
                "YouTube Music bootstrap parse failed",
                "VISITOR_DATA",
                "visitorData"
            ),
            sessionIndex = bootstrapSource.optionalNumber("SESSION_INDEX").ifBlank { "0" },
            loggedIn = loggedIn,
            userSessionId = bootstrapSource.optionalString("USER_SESSION_ID")
                .ifBlank { derivedUserSessionId },
            cookieHeader = cookieHeader,
            authFingerprint = "",
            webUserAgent = userAgent,
            fetchedAtMs = now
        )
    }

    internal fun parseLibraryPlaylists(root: JSONObject): List<YouTubeMusicLibraryPlaylist> {
        val gridItems = findLibraryGridRenderer(root)?.optJSONArray("items")
        val gridPlaylists = parseLibraryPlaylistItems(
            items = gridItems,
            requirePlaylistEndpoint = false
        )
        if (gridPlaylists.isNotEmpty()) {
            return gridPlaylists
        }

        return collectLibraryPlaylistRenderers(root)
            .mapNotNull { renderer ->
                parseLibraryPlaylistRenderer(
                    renderer = renderer,
                    requirePlaylistEndpoint = true
                )
            }
            .distinctBy { it.browseId }
    }

    private fun parseLibraryPlaylistItems(
        items: JSONArray?,
        requirePlaylistEndpoint: Boolean
    ): List<YouTubeMusicLibraryPlaylist> {
        return items.asObjectSequence()
            .mapNotNull { it.optJSONObject("musicTwoRowItemRenderer") }
            .mapNotNull { parseLibraryPlaylistRenderer(it, requirePlaylistEndpoint) }
            .toList()
    }

    private data class LibraryPlaylistCandidate(
        val endpoint: JSONObject,
        val browseId: String,
        val title: String
    ) {
        fun isValid(): Boolean = browseId.isNotEmpty() && title.isNotEmpty()
    }

    private fun libraryPlaylistCandidate(renderer: JSONObject): LibraryPlaylistCandidate? {
        return renderer.objectPath("navigationEndpoint", "browseEndpoint")?.let { endpoint ->
            LibraryPlaylistCandidate(
                endpoint = endpoint,
                browseId = endpoint.optString("browseId", "").trim(),
                title = extractText(renderer.optJSONObject("title"))
            ).takeIf(LibraryPlaylistCandidate::isValid)
        }
    }

    private fun parseLibraryPlaylistRenderer(
        renderer: JSONObject,
        requirePlaylistEndpoint: Boolean
    ): YouTubeMusicLibraryPlaylist? {
        val candidate = libraryPlaylistCandidate(renderer) ?: return null
        if (requirePlaylistEndpoint && !isMusicPlaylistBrowseEndpoint(candidate.browseId, candidate.endpoint)) {
            return null
        }
        val subtitle = extractText(renderer.optJSONObject("subtitle"))
        return YouTubeMusicLibraryPlaylist(
            browseId = candidate.browseId,
            playlistId = playlistIdFromBrowseId(candidate.browseId),
            title = candidate.title,
            subtitle = subtitle,
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnailRenderer")),
            trackCount = parseTrackCount(subtitle)
        )
    }

    internal fun extractLibraryContinuation(root: JSONObject): String? {
        return extractContinuationToken(findLibraryGridRenderer(root))
    }

    internal fun parseHomeShelfPages(root: JSONObject): List<ParsedYouTubeMusicHomeShelf> {
        return findHomeSections(root).asObjectSequence()
            .mapNotNull { it.optJSONObject("musicCarouselShelfRenderer") }
            .mapNotNull(::parseHomeShelf)
            .toList()
    }

    private fun parseHomeShelf(carousel: JSONObject): ParsedYouTubeMusicHomeShelf? {
        val header = carousel.objectPath("header", "musicCarouselShelfBasicHeaderRenderer")
            ?: return null
        val items = parseHomeItems(carousel.optJSONArray("contents"))
        return items.takeIf { it.isNotEmpty() }?.let {
            ParsedYouTubeMusicHomeShelf(
                title = extractText(header.optJSONObject("title")),
                items = it,
                continuation = extractContinuationToken(carousel)
            )
        }
    }

    internal fun extractHomeContinuation(root: JSONObject): String? {
        return extractContinuationToken(findHomeSectionListRenderer(root))
    }

    internal fun parseHomeShelfContinuationItems(root: JSONObject): List<YouTubeMusicHomeItem> {
        return parseHomeItems(findHomeShelfContinuationRenderer(root)?.optJSONArray("contents"))
    }

    internal fun extractHomeShelfContinuation(root: JSONObject): String? {
        return extractContinuationToken(findHomeShelfContinuationRenderer(root))
    }

    fun parseHomePlaylistRecommendations(
        shelves: List<YouTubeMusicHomeShelf>,
        limit: Int = YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT
    ): List<YouTubeMusicLibraryPlaylist> {
        val resultLimit = limit.coerceAtLeast(1)
        val seenBrowseIds = linkedSetOf<String>()
        return buildList {
            for (shelf in shelves) {
                for (item in shelf.items) {
                    val browseId = item.browseId.trim()
                    if (!item.isHomePlaylistCard(browseId) || !seenBrowseIds.add(browseId)) {
                        continue
                    }
                    add(
                        YouTubeMusicLibraryPlaylist(
                            browseId = browseId,
                            playlistId = playlistIdFromBrowseId(browseId),
                            title = item.title,
                            subtitle = item.subtitle.ifBlank { shelf.title },
                            coverUrl = item.coverUrl,
                            trackCount = parseTrackCount(item.subtitle)
                        )
                    )
                    if (size >= resultLimit) {
                        return@buildList
                    }
                }
            }
        }
    }

    fun parseHomeSongMetadata(
        subtitle: String,
        fallbackAlbum: String,
        fallbackArtist: String = "YouTube Music"
    ): YouTubeMusicHomeSongMetadata {
        val metadataParts = subtitle
            .split('•', '·', '|')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot(::looksLikeHomeSongTypeLabel)
            .filterNot(::looksLikeDurationText)
            .filterNot(::looksLikeSearchStatText)
            .toList()

        val artist = metadataParts.firstOrNull().orEmpty().ifBlank { fallbackArtist }
        val album = metadataParts.drop(1).firstOrNull().orEmpty().ifBlank { fallbackAlbum }
        return YouTubeMusicHomeSongMetadata(
            artist = artist,
            album = album
        )
    }

    internal fun parsePlaylistDetail(
        root: JSONObject,
        browseId: String,
        fallbackTitle: String,
        fallbackSubtitle: String,
        fallbackCoverUrl: String
    ): YouTubeMusicPlaylistDetail {
        val header = findPlaylistHeaderRenderer(root)
        val playlistShelf = findPlaylistShelfRenderer(root)

        return YouTubeMusicPlaylistDetail(
            browseId = browseId,
            playlistId = playlistIdFromShelf(playlistShelf, browseId),
            title = playlistHeaderText(header, "title", fallbackTitle),
            subtitle = playlistHeaderText(header, "subtitle", fallbackSubtitle),
            coverUrl = playlistHeaderCover(header, fallbackCoverUrl),
            trackCount = parsePlaylistTrackCount(root),
            tracks = parsePlaylistTracks(root)
        )
    }

    private fun playlistIdFromShelf(shelf: JSONObject?, browseId: String): String {
        return shelf?.optString("playlistId", "")
            ?.ifBlank { playlistIdFromBrowseId(browseId) }
            .orEmpty()
    }

    private fun playlistHeaderText(header: JSONObject?, key: String, fallback: String): String {
        return extractText(header?.optJSONObject(key)).ifBlank { fallback }
    }

    private fun playlistHeaderCover(header: JSONObject?, fallback: String): String {
        return extractMusicThumbnailUrl(header?.optJSONObject("thumbnail")).ifBlank { fallback }
    }

    internal fun parsePlaylistTracks(root: JSONObject): List<YouTubeMusicPlaylistTrack> {
        return parsePlaylistPage(root).tracks
    }

    internal fun parsePlaylistPage(root: JSONObject): YouTubeMusicPlaylistPage {
        val contents = findPlaylistPageItems(root)
        return YouTubeMusicPlaylistPage(
            tracks = parsePlaylistTracks(contents),
            continuation = extractContinuationToken(findPlaylistShelfRenderer(root))
                ?: extractContinuationTokenFromItems(contents)
        )
    }

    private fun parsePlaylistTracks(contents: JSONArray?): List<YouTubeMusicPlaylistTrack> {
        return contents.asObjectSequence()
            .mapNotNull { it.optJSONObject("musicResponsiveListItemRenderer") }
            .mapNotNull(::parsePlaylistTrack)
            .toList()
    }

    private fun parsePlaylistTrack(renderer: JSONObject): YouTubeMusicPlaylistTrack? {
        val videoId = extractTrackVideoId(renderer)
        val flex = renderer.optJSONArray("flexColumns")
        val title = extractColumnText(flex, 0, "musicResponsiveListItemFlexColumnRenderer")
        val durationText = playlistTrackDuration(renderer)
        return YouTubeMusicPlaylistTrack(
            videoId = videoId,
            title = title,
            artist = extractColumnText(flex, 1, "musicResponsiveListItemFlexColumnRenderer"),
            album = extractColumnText(flex, 2, "musicResponsiveListItemFlexColumnRenderer"),
            durationText = durationText,
            durationMs = parseDurationTextToMs(durationText),
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail"))
        ).takeIf { videoId.isNotBlank() && title.isNotBlank() }
    }

    private fun playlistTrackDuration(renderer: JSONObject): String {
        val fixed = extractColumnText(
            renderer.optJSONArray("fixedColumns"), 0, "musicResponsiveListItemFixedColumnRenderer"
        )
        return fixed.takeIf { it.isNotBlank() && it.contains(':') }
            ?: findPlaylistFlexDuration(renderer.optJSONArray("flexColumns"))
            ?: fixed
    }

    private fun findPlaylistFlexDuration(columns: JSONArray?): String? {
        return columns?.let { items ->
            (0 until items.length()).asSequence()
                .map { extractColumnText(items, it, "musicResponsiveListItemFlexColumnRenderer") }
                .firstOrNull(::isPlaylistFlexDuration)
        }
    }

    private fun isPlaylistFlexDuration(text: String): Boolean {
        return text.contains(':') && text.split(':').all { it.toLongOrNull() != null }
    }

    internal fun extractPlaylistContinuation(root: JSONObject): String? {
        return parsePlaylistPage(root).continuation
    }

    internal fun parsePlaylistTrackCount(root: JSONObject): Int? {
        return playlistHeaderTrackCount(root) ?: completedPlaylistPageTrackCount(root)
    }

    private fun playlistHeaderTrackCount(root: JSONObject): Int? {
        val header = findPlaylistHeaderRenderer(root)
        return parseTrackCount(
            playlistHeaderText(
                header, "secondSubtitle", playlistHeaderText(header, "subtitle", "")
            )
        )
    }

    private fun completedPlaylistPageTrackCount(root: JSONObject): Int? {
        val page = parsePlaylistPage(root)
        return page.tracks.size.takeIf { it > 0 && page.continuation.isNullOrBlank() }
    }

    internal fun parseDurationTextToMs(durationText: String): Long {
        val parts = durationText.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) {
            return 0L
        }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> return 0L
        }
        return seconds * 1000L
    }

    internal fun parseTrackCount(subtitle: String): Int? {
        val normalized = subtitle.trim()
        if (normalized.isBlank()) {
            return null
        }
        return Regex(
            pattern = "([0-9][0-9,]*)\\s*(?:首歌|首歌曲?|首|曲|集|songs?|tracks?|videos?|episodes?)",
            option = RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
    }

    private fun findLibraryGridRenderer(root: JSONObject): JSONObject? {
        val sections = browseTabSections(root, "singleColumnBrowseResultsRenderer")
        val grid = sections?.let { items ->
            (0 until items.length()).asSequence()
                .mapNotNull(items::optJSONObject)
                .mapNotNull { it.optJSONObject("gridRenderer") }
                .firstOrNull()
        }
        return grid ?: root.objectPath("continuationContents", "gridContinuation")
    }

    private fun collectLibraryPlaylistRenderers(root: JSONObject): List<JSONObject> {
        val renderers = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("musicTwoRowItemRenderer")?.let(renderers::add)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        visit(value.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index))
                    }
                }
            }
        }
        visit(root)
        return renderers
    }

    private fun findHomeSectionListRenderer(root: JSONObject): JSONObject? {
        return homeTabSectionList(root)
            ?: root.objectPath("continuationContents", "sectionListContinuation")
    }

    private fun homeTabSectionList(root: JSONObject): JSONObject? {
        return root.objectPath("contents", "singleColumnBrowseResultsRenderer")
            ?.let(::firstBrowseTabSectionList)
    }

    private fun findHomeSections(root: JSONObject): JSONArray? {
        return findHomeSectionListRenderer(root)?.optJSONArray("contents")
    }

    private fun findPlaylistHeaderRenderer(root: JSONObject): JSONObject? {
        return playlistTabSections(root).asObjectSequence()
            .mapNotNull(::playlistHeaderInSection)
            .firstOrNull()
    }

    private fun playlistHeaderInSection(section: JSONObject): JSONObject? {
        return section.optJSONObject("musicResponsiveHeaderRenderer")
            ?: section.objectPath(
                "musicEditablePlaylistDetailHeaderRenderer", "header", "musicResponsiveHeaderRenderer"
            )
    }

    private fun playlistTabSections(root: JSONObject): JSONArray? {
        return browseTabSections(root, "twoColumnBrowseResultsRenderer")
    }

    private fun findPlaylistShelfRenderer(root: JSONObject): JSONObject? {
        val secondary = root.objectPath(
            "contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer"
        )?.optJSONArray("contents")
        // 一些歌单响应不会把曲目 shelf 放进 secondaryContents, 需要回退到主内容区扫描
        val primary = playlistTabSections(root)
        return sequenceOf(
            scanPlaylistSections(secondary),
            scanPlaylistSections(primary),
            root.objectPath("continuationContents", "musicPlaylistShelfContinuation"),
            root.objectPath("continuationContents", "musicShelfContinuation")
        ).filterNotNull().firstOrNull()
    }

    private fun findPlaylistPageItems(root: JSONObject): JSONArray? {
        return findPlaylistShelfRenderer(root)?.optJSONArray("contents")
            ?: root.optJSONArray("onResponseReceivedActions").asObjectSequence()
                .mapNotNull { it.objectPath("appendContinuationItemsAction")?.optJSONArray("continuationItems") }
                .firstOrNull()
    }

    private fun findHomeShelfContinuationRenderer(root: JSONObject): JSONObject? {
        val continuationContents = root.optJSONObject("continuationContents")
        return sequenceOf(
            "musicShelfContinuation",
            "musicCarouselShelfContinuation",
            "musicPlaylistShelfContinuation"
        ).mapNotNull { continuationContents?.optJSONObject(it) }.firstOrNull()
    }

    private fun parseHomeItems(contents: JSONArray?): List<YouTubeMusicHomeItem> {
        return contents.asObjectSequence().mapNotNull(::parseHomeItem).toList()
    }

    private fun parseHomeItem(item: JSONObject): YouTubeMusicHomeItem? {
        item.optJSONObject("musicTwoRowItemRenderer")?.let { return parseHomeTwoRowItem(it) }
        return item.optJSONObject("musicResponsiveListItemRenderer")?.let(::parseHomeListItem)
    }

    private data class HomeItemRoute(
        val browseId: String,
        val videoId: String,
        val pageType: String
    )

    private fun homeItemRoute(renderer: JSONObject): HomeItemRoute {
        val navigation = renderer.optJSONObject("navigationEndpoint")
        val browse = navigation?.optJSONObject("browseEndpoint")
        val browseRoute = homeBrowseRoute(browse)
        return HomeItemRoute(
            browseId = browseRoute.browseId,
            videoId = navigation.objectPath("watchEndpoint")?.optString("videoId", "").orEmpty(),
            pageType = browseRoute.pageType
        )
    }

    private data class HomeBrowseRoute(val browseId: String, val pageType: String)

    private fun homeBrowseRoute(browse: JSONObject?): HomeBrowseRoute {
        return HomeBrowseRoute(
            browseId = browse?.optString("browseId", "").orEmpty(),
            pageType = browse.objectPath(
                "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig"
            )?.optString("pageType").orEmpty()
        )
    }

    private fun parseHomeTwoRowItem(renderer: JSONObject): YouTubeMusicHomeItem? {
        val title = extractText(renderer.optJSONObject("title"))
        if (title.isBlank()) return null
        val subtitleNode = renderer.optJSONObject("subtitle")
        val durationText = extractDurationText(subtitleNode)
        val route = homeItemRoute(renderer)
        return YouTubeMusicHomeItem(
            title = title,
            subtitle = extractText(subtitleNode),
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnailRenderer")),
            browseId = route.browseId,
            videoId = route.videoId,
            pageType = route.pageType,
            durationText = durationText,
            durationMs = parseDurationTextToMs(durationText)
        )
    }

    private fun parseHomeListItem(renderer: JSONObject): YouTubeMusicHomeItem? {
        val flex = renderer.optJSONArray("flexColumns")
        val title = extractColumnText(flex, 0, "musicResponsiveListItemFlexColumnRenderer")
        if (title.isBlank()) return null
        val durationText = findDurationText(
            renderer.optJSONArray("fixedColumns"), "musicResponsiveListItemFixedColumnRenderer"
        ).ifBlank { findDurationText(flex, "musicResponsiveListItemFlexColumnRenderer") }
        return YouTubeMusicHomeItem(
            title = title,
            subtitle = extractColumnText(flex, 1, "musicResponsiveListItemFlexColumnRenderer"),
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail")),
            videoId = extractTrackVideoId(renderer),
            durationText = durationText,
            durationMs = parseDurationTextToMs(durationText)
        )
    }

    private fun scanPlaylistSections(sections: JSONArray?): JSONObject? {
        if (sections == null) {
            return null
        }
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            section.optJSONObject("musicPlaylistShelfRenderer")?.let { return it }
            section.optJSONObject("musicShelfRenderer")?.let { return it }
        }
        return null
    }

    internal fun hasSongSearchShelf(root: JSONObject): Boolean {
        return findSearchSongShelfRenderer(root) != null
    }

    internal fun hasSearchShelf(root: JSONObject): Boolean {
        return findSearchSongShelfRenderer(root) != null
    }

    internal fun parseSongSearchResults(
        root: JSONObject,
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
    ): List<YouTubeMusicSearchResult> {
        return parseFilteredSearchResults(
            root = root,
            type = YouTubeMusicSearchResultType.Song,
            limit = limit
        )
    }

    internal fun parseVideoSearchResults(
        root: JSONObject,
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
    ): List<YouTubeMusicSearchResult> {
        return parseFilteredSearchResults(
            root = root,
            type = YouTubeMusicSearchResultType.Video,
            limit = limit
        )
    }

    internal fun parseCreatorSearchResults(
        root: JSONObject,
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
    ): List<YouTubeMusicCreatorSummary> {
        return findSearchResultContentArrays(root)
            .flatMap(::parseCreatorSearchItems)
            .distinctBy { it.browseId }
            .take(limit.coerceAtLeast(1))
    }

    internal fun parseFollowedArtistsPage(root: JSONObject): YouTubeMusicFollowedArtistsPage? {
        val source = findFollowedArtistsContinuationSource(root)
            ?: findFollowedArtistsPageSource(root)
            ?: return null
        val contents = source.contents ?: return null
        return YouTubeMusicFollowedArtistsPage(
            artists = contents.asObjectSequence()
                .mapNotNull(::parseFollowedArtistItem)
                .distinctBy { it.browseId }
                .toList(),
            continuation = source.continuation
        )
    }

    private fun findFollowedArtistsContinuationSource(root: JSONObject): CreatorItemsSource? {
        if (root.objectPath("continuationContents", "musicCarouselShelfContinuation") != null) {
            return null
        }
        return findCreatorItemsContinuationSource(root)
    }

    private fun findFollowedArtistsPageSource(root: JSONObject): CreatorItemsSource? {
        val contents = root.optJSONObject("contents")
        val directSections = contents.objectPath("sectionListRenderer")?.optJSONArray("contents")
        val tabSections = sequenceOf("singleColumnBrowseResultsRenderer", "twoColumnBrowseResultsRenderer")
            .flatMap { columnKey -> followedArtistsTabSections(contents?.optJSONObject(columnKey)) }
        return (directSections.asObjectSequence() + tabSections)
            .mapNotNull(::findFollowedArtistsSourceInSection)
            .firstOrNull()
    }

    private fun followedArtistsTabSections(column: JSONObject?): Sequence<JSONObject> {
        val tabs = column?.optJSONArray("tabs") ?: return emptySequence()
        val legacyLibraryTab = if (tabs.length() < 3) 1 else 2
        return sequenceOf(0, legacyLibraryTab)
            .mapNotNull { index ->
                tabs.optJSONObject(index)
                    .objectPath("tabRenderer", "content", "sectionListRenderer")
                    ?.optJSONArray("contents")
            }
            .flatMap { it.asObjectSequence() }
    }

    private fun findFollowedArtistsSourceInSection(section: JSONObject): CreatorItemsSource? {
        val nested = section.objectPath("itemSectionRenderer")?.optJSONArray("contents")
        return (sequenceOf(section) + nested.asObjectSequence())
            .mapNotNull(::findFollowedArtistsSource)
            .firstOrNull()
    }

    private fun findFollowedArtistsSource(section: JSONObject): CreatorItemsSource? {
        return section.optJSONObject("gridRenderer")?.let(::creatorGridSource)
            ?: section.optJSONObject("musicPlaylistShelfRenderer")?.let(::creatorShelfSource)
            ?: section.optJSONObject("musicShelfRenderer")?.let(::creatorShelfSource)
    }

    private fun parseFollowedArtistItem(item: JSONObject): YouTubeMusicCreatorSummary? {
        val row = creatorSearchRow(item) ?: return null
        val endpoint = extractBrowseEndpoint(row.renderer) ?: return null
        val browseId = endpoint.optString("browseId").trim()
        if (resolveCreatorBrowseItemType(endpoint, browseId) != YouTubeMusicCreatorItemType.Creator ||
            row.renderer.objectPath("navigationEndpoint", "watchEndpoint") != null ||
            extractTrackVideoId(row.renderer).isNotBlank()
        ) {
            return null
        }
        return creatorSearchSummary(row)
    }

    internal fun parseCreatorDetail(
        root: JSONObject,
        fallback: YouTubeMusicCreatorSummary
    ): YouTubeMusicCreatorDetail {
        val headerRenderer = findCreatorHeaderRenderer(root)
        val title = creatorHeaderText(headerRenderer, "title").ifBlank { fallback.title }
        val subtitle = firstNonBlank(
            creatorHeaderText(headerRenderer, "subtitle"),
            creatorHeaderText(headerRenderer, "straplineTextOne"),
            fallback.subtitle
        )
        val coverUrl = firstNonBlank(
            creatorHeaderCover(headerRenderer, "thumbnail"),
            creatorHeaderCover(headerRenderer, "thumbnailRenderer"),
            fallback.coverUrl
        )
        val description = firstNonBlank(
            creatorHeaderText(headerRenderer, "description"),
            findCreatorDescription(root)
        )
        val header = YouTubeMusicCreatorHeader(
            browseId = fallback.browseId,
            title = title,
            subtitle = subtitle,
            coverUrl = coverUrl,
            description = description,
            subscriberCountText = creatorHeaderText(headerRenderer, "shortSubscriberCountText"),
            monthlyListenerCountText = creatorHeaderText(headerRenderer, "monthlyListenerCount")
        )
        return YouTubeMusicCreatorDetail(
            header = header,
            sections = findCreatorSectionContents(root)
                .flatMap(::parseCreatorSections)
                .filter { it.items.isNotEmpty() }
        )
    }

    private fun creatorHeaderText(header: JSONObject?, key: String): String {
        return extractText(header?.optJSONObject(key))
    }

    private fun creatorHeaderCover(header: JSONObject?, key: String): String {
        return extractMusicThumbnailUrl(header?.optJSONObject(key))
    }

    private fun parseFilteredSearchResults(
        root: JSONObject,
        type: YouTubeMusicSearchResultType,
        limit: Int
    ): List<YouTubeMusicSearchResult> {
        return findSearchResultContentArrays(root)
            .flatMap { contents ->
                parseSearchRendererItems(
                    contents = contents,
                    forcedType = type
                )
            }
            .distinctBy { it.videoId }
            .take(limit.coerceAtLeast(1))
    }

    internal fun extractSearchContinuation(root: JSONObject): String? {
        return extractContinuationToken(findSearchSongShelfRenderer(root))
            ?: extractContinuationToken(root.objectPath("continuationContents", "musicShelfContinuation"))
    }

    private fun extractTrackVideoId(renderer: JSONObject): String {
        return firstNonBlank(
            renderer.objectPath(
                "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint", "watchEndpoint"
            )
                ?.optString("videoId"),
            renderer.optJSONObject("playlistItemData")?.optString("videoId"),
            extractVideoIdFromTextRuns(
                renderer.optJSONArray("flexColumns")
                    ?.optJSONObject(0)
                    .objectPath("musicResponsiveListItemFlexColumnRenderer", "text")
                    ?.optJSONArray("runs")
            ),
            extractVideoIdFromMenu(renderer.optJSONObject("menu"))
        )
    }

    private fun extractVideoIdFromTextRuns(runs: JSONArray?): String {
        return runs.asObjectSequence()
            .map { it.objectPath("navigationEndpoint", "watchEndpoint")?.optString("videoId").orEmpty() }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun extractVideoIdFromMenu(menu: JSONObject?): String {
        val items = menu.objectPath("menuRenderer")?.optJSONArray("items") ?: return ""
        return (0 until items.length()).asSequence()
            .mapNotNull(items::optJSONObject)
            .map(::extractMenuItemVideoId)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun extractMenuItemVideoId(item: JSONObject): String {
        return firstNonBlank(
            item.objectPath("menuNavigationItemRenderer", "navigationEndpoint", "watchEndpoint")
                ?.optString("videoId"),
            queueTargetVideoId(item)
        )
    }

    private fun queueTargetVideoId(item: JSONObject): String {
        val target = item.objectPath(
            "menuServiceItemRenderer", "serviceEndpoint", "queueAddEndpoint", "queueTarget"
        )
        return firstNonBlank(
            target?.optString("videoId"),
            target.objectPath("onEmptyQueue", "watchEndpoint")?.optString("videoId")
        )
    }

    private fun findSearchSectionListRenderer(root: JSONObject): JSONObject? {
        return root.objectPath("contents", "tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            .objectPath("tabRenderer", "content", "sectionListRenderer")
    }

    private fun findSearchSongShelfRenderer(root: JSONObject): JSONObject? {
        return findSearchShelfRenderers(root).firstOrNull()
    }

    private fun findSearchResultContentArrays(root: JSONObject): List<JSONArray> {
        val shelves = findSearchShelfRenderers(root).mapNotNull { it.optJSONArray("contents") }
        val sections = findSearchSectionListRenderer(root)?.optJSONArray("contents")
        val itemSections = sections.asObjectSequence()
            .mapNotNull { it.objectPath("itemSectionRenderer")?.optJSONArray("contents") }
            .toList()
        val continuation = root.objectPath("continuationContents", "musicShelfContinuation")
            ?.optJSONArray("contents")
        return shelves + itemSections + listOfNotNull(continuation)
    }

    private fun findSearchShelfRenderers(root: JSONObject): List<JSONObject> {
        return findSearchSectionListRenderer(root)?.optJSONArray("contents").asObjectSequence()
            .flatMap(::searchShelvesInSection)
            .toList()
    }

    private fun searchShelvesInSection(section: JSONObject): Sequence<JSONObject> {
        val direct = section.optJSONObject("musicShelfRenderer")
        val nested = section.objectPath("itemSectionRenderer")?.optJSONArray("contents")
        return listOfNotNull(direct).asSequence() + nested.asObjectSequence()
            .mapNotNull { it.optJSONObject("musicShelfRenderer") }
    }

    private fun parseSearchRendererItems(
        contents: JSONArray?,
        forcedType: YouTubeMusicSearchResultType? = null
    ): List<YouTubeMusicSearchResult> {
        return contents.asObjectSequence()
            .mapNotNull { it.optJSONObject("musicResponsiveListItemRenderer") }
            .mapNotNull { parseSearchResult(it, forcedType) }
            .toList()
    }

    private fun parseCreatorSearchItems(contents: JSONArray): List<YouTubeMusicCreatorSummary> {
        return contents.asObjectSequence().mapNotNull(::parseCreatorSearchItem).toList()
    }

    private fun parseCreatorSearchItem(item: JSONObject): YouTubeMusicCreatorSummary? {
        return creatorSearchRow(item)?.let(::creatorSearchSummary)
    }

    private data class CreatorSearchRow(
        val renderer: JSONObject,
        val title: String,
        val subtitle: String
    )

    private fun creatorSearchRow(item: JSONObject): CreatorSearchRow? {
        val renderer = creatorSearchRenderer(item) ?: return null
        val isTwoRow = item.has("musicTwoRowItemRenderer")
        return CreatorSearchRow(
            renderer = renderer,
            title = creatorSearchText(renderer, isTwoRow, 0),
            subtitle = creatorSearchText(renderer, isTwoRow, 1)
        ).takeIf { it.title.isNotBlank() }
    }

    private fun creatorSearchRenderer(item: JSONObject): JSONObject? {
        return item.optJSONObject("musicTwoRowItemRenderer")
            ?: item.optJSONObject("musicResponsiveListItemRenderer")
    }

    private fun creatorSearchText(renderer: JSONObject, isTwoRow: Boolean, column: Int): String {
        return if (isTwoRow) {
            extractText(renderer.optJSONObject(CREATOR_SEARCH_TEXT_KEYS[column]))
        } else {
            extractColumnText(
                renderer.optJSONArray("flexColumns"), column, "musicResponsiveListItemFlexColumnRenderer"
            )
        }
    }

    private fun creatorSearchSummary(row: CreatorSearchRow): YouTubeMusicCreatorSummary? {
        val renderer = row.renderer
        val browseId = extractBrowseEndpoint(renderer)?.let(::validatedCreatorBrowseId)
            ?: return null
        return YouTubeMusicCreatorSummary(
            browseId = browseId,
            title = row.title,
            subtitle = row.subtitle,
            coverUrl = firstNonBlank(
                extractMusicThumbnailUrl(renderer.optJSONObject("thumbnailRenderer")),
                extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail"))
            ),
            channelId = extractCreatorChannelId(renderer, browseId)
        )
    }

    private fun validatedCreatorBrowseId(endpoint: JSONObject): String? {
        return endpoint.optString("browseId").trim().takeIf(String::isNotBlank)
    }

    private fun findCreatorHeaderRenderer(root: JSONObject): JSONObject? {
        val header = root.optJSONObject("header")
        return sequenceOf(
            "musicImmersiveHeaderRenderer",
            "musicVisualHeaderRenderer",
            "musicDetailHeaderRenderer"
        ).mapNotNull { header?.optJSONObject(it) }.firstOrNull()
    }

    private fun findCreatorDescription(root: JSONObject): String {
        return findCreatorSectionContents(root).asSequence()
            .map { extractText(it.objectPath("musicDescriptionShelfRenderer", "description")) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun findCreatorSectionContents(root: JSONObject): List<JSONObject> {
        val candidateArrays = sequenceOf(
            browseTabSections(root, "singleColumnBrowseResultsRenderer"),
            root.objectPath("contents", "sectionListRenderer")
                ?.optJSONArray("contents"),
            browseTabSections(root, "twoColumnBrowseResultsRenderer")
        )
        val contents = candidateArrays.filterNotNull().firstOrNull { it.length() > 0 }
        return contents.asObjectSequence().toList()
    }

    private fun browseTabSections(root: JSONObject, columnKey: String): JSONArray? {
        return root.objectPath("contents", columnKey)?.let(::firstBrowseTabSections)
    }

    private fun firstBrowseTabSections(column: JSONObject): JSONArray? {
        return firstBrowseTabSectionList(column)?.optJSONArray("contents")
    }

    private fun firstBrowseTabSectionList(column: JSONObject): JSONObject? {
        return column.optJSONArray("tabs")
            ?.optJSONObject(0)
            .objectPath("tabRenderer", "content", "sectionListRenderer")
    }

    private fun parseCreatorSections(section: JSONObject): List<YouTubeMusicCreatorSection> {
        val nested = section.objectPath("itemSectionRenderer")?.optJSONArray("contents")
        val sections = sequenceOf(section) + nested.asObjectSequence()
        return sections.flatMap(::parseCreatorSectionRenderers).toList()
    }

    private fun JSONArray?.asObjectSequence(): Sequence<JSONObject> =
        this?.let { items ->
            (0 until items.length()).asSequence().mapNotNull(items::optJSONObject)
        } ?: emptySequence()

    private fun parseCreatorSectionRenderers(section: JSONObject): Sequence<YouTubeMusicCreatorSection> {
        return sequenceOf(
            section.optJSONObject("musicShelfRenderer")?.let(::parseCreatorShelf),
            section.optJSONObject("musicCarouselShelfRenderer")?.let(::parseCreatorCarousel)
        ).filterNotNull()
    }

    private fun parseCreatorShelf(renderer: JSONObject): YouTubeMusicCreatorSection? {
        val title = extractText(renderer.optJSONObject("title"))
        if (title.isBlank()) {
            return null
        }
        return YouTubeMusicCreatorSection(
            title = title,
            items = parseCreatorSectionItems(renderer.optJSONArray("contents")),
            moreEndpoint = extractCreatorMoreEndpoint(
                renderer = renderer,
                titleNode = renderer.optJSONObject("title")
            )
        )
    }

    private fun parseCreatorCarousel(renderer: JSONObject): YouTubeMusicCreatorSection? {
        val header = renderer.optJSONObject("header")
        val basicHeader = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        val title = creatorCarouselTitle(renderer, header, basicHeader)
            .takeIf(String::isNotBlank) ?: return null
        return YouTubeMusicCreatorSection(
            title = title,
            items = parseCreatorSectionItems(renderer.optJSONArray("contents")),
            moreEndpoint = extractCreatorMoreEndpoint(
                renderer = renderer,
                titleNode = creatorCarouselTitleNode(basicHeader, header),
                header = basicHeader
            )
        )
    }

    private fun creatorCarouselTitle(
        renderer: JSONObject,
        header: JSONObject?,
        basicHeader: JSONObject?
    ): String {
        return sequenceOf(basicHeader, header, renderer)
            .filterNotNull()
            .map { extractText(it.optJSONObject("title")) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun creatorCarouselTitleNode(basicHeader: JSONObject?, header: JSONObject?): JSONObject? {
        return sequenceOf(basicHeader, header)
            .filterNotNull()
            .mapNotNull { it.optJSONObject("title") }
            .firstOrNull()
    }

    internal fun parseCreatorItemsPage(
        root: JSONObject,
        fallbackTitle: String
    ): YouTubeMusicCreatorItemsPage {
        return findCreatorItemsPageSource(root)
            ?.toCreatorItemsPage(fallbackTitle)
            ?: YouTubeMusicCreatorItemsPage(
                title = fallbackTitle,
                items = emptyList()
            )
    }

    internal fun parseCreatorItemsContinuation(root: JSONObject): YouTubeMusicCreatorItemsPage {
        return findCreatorItemsContinuationSource(root)
            ?.toCreatorItemsPage(fallbackTitle = "")
            ?: YouTubeMusicCreatorItemsPage(title = "", items = emptyList())
    }

    private data class CreatorItemsSource(
        val title: String,
        val contents: JSONArray?,
        val continuation: String?
    )

    private fun CreatorItemsSource.toCreatorItemsPage(
        fallbackTitle: String
    ): YouTubeMusicCreatorItemsPage {
        val items = parseCreatorSectionItems(contents)
        return YouTubeMusicCreatorItemsPage(
            title = title.ifBlank { fallbackTitle },
            items = items,
            continuation = continuation.takeIf { items.isNotEmpty() }
        )
    }

    private fun findCreatorItemsPageSource(root: JSONObject): CreatorItemsSource? {
        for (section in findCreatorSectionContents(root)) {
            findCreatorItemsPageSourceInSection(section)?.let { return it }
        }
        return null
    }

    private fun findCreatorItemsPageSourceInSection(section: JSONObject): CreatorItemsSource? {
        return findCreatorItemsSource(section)
            ?: section.objectPath("itemSectionRenderer")?.optJSONArray("contents")
                .asObjectSequence()
                .mapNotNull(::findCreatorItemsSource)
                .firstOrNull()
    }

    private fun findCreatorItemsSource(section: JSONObject): CreatorItemsSource? {
        val variants = listOf(
            "gridRenderer" to ::creatorGridSource,
            "musicCarouselShelfRenderer" to ::creatorCarouselSource,
            "musicPlaylistShelfRenderer" to ::creatorShelfSource,
            "musicShelfRenderer" to ::creatorShelfSource
        )
        return variants.firstNotNullOfOrNull { (key, parse) ->
            section.optJSONObject(key)?.let(parse)
        }
    }

    private fun creatorGridSource(renderer: JSONObject): CreatorItemsSource {
        return creatorItemsSource(
            title = extractText(renderer.objectPath("header", "gridHeaderRenderer", "title")),
            renderer = renderer,
            contents = renderer.optJSONArray("items")
        )
    }

    private fun creatorCarouselSource(renderer: JSONObject): CreatorItemsSource {
        val header = renderer.optJSONObject("header")
        return creatorItemsSource(
            title = firstNonBlank(
                extractText(header.objectPath("musicCarouselShelfBasicHeaderRenderer", "title")),
                extractText(header?.optJSONObject("title")),
                extractText(renderer.optJSONObject("title"))
            ),
            renderer = renderer,
            contents = renderer.optJSONArray("contents")
        )
    }

    private fun creatorShelfSource(renderer: JSONObject): CreatorItemsSource {
        return creatorItemsSource(
            title = extractText(renderer.optJSONObject("title")),
            renderer = renderer,
            contents = renderer.optJSONArray("contents")
        )
    }

    private fun findCreatorItemsContinuationSource(root: JSONObject): CreatorItemsSource? {
        return findCreatorRendererContinuation(root) ?: findCreatorActionContinuation(root)
    }

    private fun findCreatorRendererContinuation(root: JSONObject): CreatorItemsSource? {
        val contents = root.optJSONObject("continuationContents")
        val rendererKeys = listOf(
            "gridContinuation" to "items",
            "musicPlaylistShelfContinuation" to "contents",
            "musicShelfContinuation" to "contents",
            "musicCarouselShelfContinuation" to "contents"
        )
        return rendererKeys.firstNotNullOfOrNull { (rendererKey, itemsKey) ->
            contents?.optJSONObject(rendererKey)?.let { renderer ->
                creatorItemsSource("", renderer, renderer.optJSONArray(itemsKey))
            }
        }
    }

    private fun findCreatorActionContinuation(root: JSONObject): CreatorItemsSource? {
        return root.optJSONArray("onResponseReceivedActions").asObjectSequence()
            .mapNotNull { it.objectPath("appendContinuationItemsAction")?.optJSONArray("continuationItems") }
            .map { continuationItems ->
                CreatorItemsSource(
                    title = "",
                    contents = continuationItems,
                    continuation = extractContinuationTokenFromItems(continuationItems)
                )
            }
            .firstOrNull()
    }

    private fun creatorItemsSource(
        title: String,
        renderer: JSONObject,
        contents: JSONArray?
    ): CreatorItemsSource {
        return CreatorItemsSource(
            title = title,
            contents = contents,
            continuation = extractContinuationToken(renderer)
                ?: extractContinuationTokenFromItems(contents)
        )
    }

    private fun extractCreatorMoreEndpoint(
        renderer: JSONObject,
        titleNode: JSONObject?,
        header: JSONObject? = null
    ): YouTubeMusicCreatorBrowseEndpoint? {
        return sequenceOf(
            renderer.objectPath("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint"),
            header.objectPath("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint"),
            renderer.objectPath("bottomEndpoint", "browseEndpoint"),
            extractCreatorTitleBrowseEndpoint(titleNode)
        ).mapNotNull(::extractCreatorBrowseEndpoint).firstOrNull()
    }

    private fun extractCreatorTitleBrowseEndpoint(titleNode: JSONObject?): JSONObject? {
        return browseEndpointInRuns(titleNode?.optJSONArray("runs"))
    }

    private fun extractCreatorBrowseEndpoint(
        browseEndpoint: JSONObject?
    ): YouTubeMusicCreatorBrowseEndpoint? {
        val browseId = browseEndpoint?.optString("browseId").orEmpty().trim()
        if (browseId.isBlank()) {
            return null
        }
        return YouTubeMusicCreatorBrowseEndpoint(
            browseId = browseId,
            params = browseEndpoint?.optString("params").orEmpty().trim()
        )
    }

    private fun parseCreatorSectionItems(contents: JSONArray?): List<YouTubeMusicCreatorItem> {
        return contents.asObjectSequence()
            .flatMap(::creatorItemsInSection)
            .toList()
    }

    private fun creatorItemsInSection(item: JSONObject): Sequence<YouTubeMusicCreatorItem> {
        return sequenceOf(
            item.optJSONObject("musicResponsiveListItemRenderer")?.let(::parseCreatorResponsiveItem),
            item.optJSONObject("musicTwoRowItemRenderer")?.let(::parseCreatorTwoRowItem)
        ).filterNotNull()
    }

    private fun parseCreatorResponsiveItem(renderer: JSONObject): YouTubeMusicCreatorItem? {
        val title = extractColumnText(
            columns = renderer.optJSONArray("flexColumns"),
            index = 0,
            rendererKey = "musicResponsiveListItemFlexColumnRenderer"
        ).takeIf(String::isNotBlank) ?: return null
        val route = creatorItemRoute(renderer, extractTrackVideoId(renderer)) ?: return null
        return buildCreatorResponsiveItem(renderer, title, route)
    }

    private data class CreatorItemRoute(
        val videoId: String,
        val browseId: String,
        val browseEndpoint: JSONObject?
    ) {
        fun hasTarget(): Boolean = videoId.isNotBlank() || browseId.isNotBlank()
    }

    private fun creatorItemRoute(renderer: JSONObject, videoId: String): CreatorItemRoute? {
        val browseEndpoint = extractBrowseEndpoint(renderer)
        val browseId = browseEndpoint?.optString("browseId").orEmpty().trim()
        return CreatorItemRoute(videoId, browseId, browseEndpoint).takeIf(CreatorItemRoute::hasTarget)
    }

    private fun creatorItemType(
        renderer: JSONObject,
        route: CreatorItemRoute,
        defaultSearchType: YouTubeMusicSearchResultType
    ): YouTubeMusicCreatorItemType {
        return if (route.videoId.isNotBlank()) {
            when (resolveSearchResultType(renderer) ?: defaultSearchType) {
                YouTubeMusicSearchResultType.Song -> YouTubeMusicCreatorItemType.Song
                YouTubeMusicSearchResultType.Video -> YouTubeMusicCreatorItemType.Video
            }
        } else {
            resolveCreatorBrowseItemType(route.browseEndpoint, route.browseId)
        }
    }

    private fun buildCreatorResponsiveItem(
        renderer: JSONObject,
        title: String,
        route: CreatorItemRoute
    ): YouTubeMusicCreatorItem {
        val type = creatorItemType(renderer, route, YouTubeMusicSearchResultType.Song)
        val metadata = parseSearchMetadata(
            renderer = renderer,
            type = if (type == YouTubeMusicCreatorItemType.Video) {
                YouTubeMusicSearchResultType.Video
            } else {
                YouTubeMusicSearchResultType.Song
            }
        )
        val durationText = metadata.durationText.ifBlank { extractSearchDurationText(renderer) }
        return YouTubeMusicCreatorItem(
            type = type,
            title = title,
            subtitle = extractColumnText(
                columns = renderer.optJSONArray("flexColumns"),
                index = 1,
                rendererKey = "musicResponsiveListItemFlexColumnRenderer"
            ),
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail")),
            videoId = route.videoId,
            browseId = route.browseId,
            playlistId = extractPlaylistId(renderer, route.browseId),
            artist = metadata.artists.joinToString(" / "),
            album = metadata.album,
            durationMs = parseDurationTextToMs(durationText)
        )
    }

    private fun parseCreatorTwoRowItem(renderer: JSONObject): YouTubeMusicCreatorItem? {
        val title = extractText(renderer.optJSONObject("title"))
        if (title.isEmpty()) return null
        val route = creatorItemRoute(renderer, creatorTwoRowVideoId(renderer)) ?: return null
        return buildCreatorTwoRowItem(renderer, title, route)
    }

    private fun creatorTwoRowVideoId(renderer: JSONObject): String {
        return firstNonBlank(
            renderer.objectPath("navigationEndpoint", "watchEndpoint")?.optString("videoId"),
            extractTrackVideoId(renderer)
        )
    }

    private fun buildCreatorTwoRowItem(
        renderer: JSONObject,
        title: String,
        route: CreatorItemRoute
    ): YouTubeMusicCreatorItem {
        val type = creatorItemType(renderer, route, YouTubeMusicSearchResultType.Video)
        val subtitleNode = renderer.optJSONObject("subtitle")
        val subtitle = extractText(subtitleNode)
        val durationText = extractDurationText(subtitleNode)
        val metadataParts = extractTextParts(subtitleNode)
            .filterNot(::looksLikeDurationText)
            .filterNot(::looksLikeHomeSongTypeLabel)
        return YouTubeMusicCreatorItem(
            type = type,
            title = title,
            subtitle = subtitle,
            coverUrl = firstNonBlank(
                extractMusicThumbnailUrl(renderer.optJSONObject("thumbnailRenderer")),
                extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail"))
            ),
            videoId = route.videoId,
            browseId = route.browseId,
            playlistId = extractPlaylistId(renderer, route.browseId),
            artist = metadataParts.firstOrNull().orEmpty(),
            durationMs = parseDurationTextToMs(durationText)
        )
    }

    private fun resolveCreatorBrowseItemType(
        browseEndpoint: JSONObject?,
        browseId: String
    ): YouTubeMusicCreatorItemType {
        val pageType = creatorBrowsePageType(browseEndpoint)
        return CREATOR_BROWSE_TYPE_RULES.firstOrNull { rule ->
            pageType.contains(rule.pageTypeToken) || browseId.startsWith(rule.browseIdPrefix)
        }?.itemType ?: YouTubeMusicCreatorItemType.Playlist
    }

    private fun creatorBrowsePageType(endpoint: JSONObject?): String {
        return endpoint.objectPath(
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig"
        )?.optString("pageType").orEmpty().uppercase(Locale.US)
    }

    private fun extractBrowseEndpoint(renderer: JSONObject): JSONObject? {
        return sequenceOf(
            renderer.objectPath("navigationEndpoint", "browseEndpoint"),
            browseEndpointInRuns(renderer.objectPath("title")?.optJSONArray("runs")),
            browseEndpointInColumns(renderer.optJSONArray("flexColumns"))
        ).filterNotNull().firstOrNull()
    }

    private fun browseEndpointInColumns(columns: JSONArray?): JSONObject? {
        return columns.asObjectSequence()
            .mapNotNull { it.objectPath("musicResponsiveListItemFlexColumnRenderer", "text")?.optJSONArray("runs") }
            .mapNotNull(::browseEndpointInRuns)
            .firstOrNull()
    }

    private fun browseEndpointInRuns(runs: JSONArray?): JSONObject? {
        return runs.asObjectSequence()
            .mapNotNull { it.objectPath("navigationEndpoint", "browseEndpoint") }
            .firstOrNull()
    }

    private fun extractCreatorChannelId(renderer: JSONObject, browseId: String): String {
        return browseId.takeIf { it.startsWith("UC") }
            ?: subscribedChannelId(renderer)
    }

    private fun subscribedChannelId(renderer: JSONObject): String {
        val menuItems = renderer.objectPath("menu", "menuRenderer")
            ?.optJSONArray("items")
        return menuItems.asObjectSequence()
            .map(::channelIdFromMenuItem)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun channelIdFromMenuItem(item: JSONObject): String {
        return channelIdsFromMenuItem(item)?.optString(0).orEmpty()
    }

    private fun channelIdsFromMenuItem(item: JSONObject): JSONArray? {
        return item.objectPath(
            "toggleMenuServiceItemRenderer", "defaultServiceEndpoint", "subscribeEndpoint"
        )?.optJSONArray("channelIds")
    }

    private fun extractPlaylistId(renderer: JSONObject, browseId: String): String {
        return firstNonBlank(
            renderer.objectPath("navigationEndpoint", "watchEndpoint")?.optString("playlistId"),
            renderer.objectPath(
                "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint"
            )?.optString("playlistId"),
            playlistIdFromBrowseCard(browseId)
        )
    }

    private fun playlistIdFromBrowseCard(browseId: String): String? {
        return browseId.takeIf { it.startsWith("VL") }?.removePrefix("VL")
    }

    private fun parseSearchResult(
        renderer: JSONObject,
        forcedType: YouTubeMusicSearchResultType? = null
    ): YouTubeMusicSearchResult? {
        val identity = searchResultIdentity(renderer, forcedType) ?: return null
        return buildSearchResult(renderer, identity)
    }

    private data class SearchResultIdentity(
        val videoId: String,
        val title: String,
        val type: YouTubeMusicSearchResultType
    ) {
        fun isValid(): Boolean = videoId.isNotEmpty() && title.isNotEmpty()
    }

    private fun searchResultIdentity(
        renderer: JSONObject,
        forcedType: YouTubeMusicSearchResultType?
    ): SearchResultIdentity? {
        val type = resolvedSearchResultType(renderer, forcedType) ?: return null
        val title = extractColumnText(
            renderer.optJSONArray("flexColumns"), 0, "musicResponsiveListItemFlexColumnRenderer"
        )
        return SearchResultIdentity(extractTrackVideoId(renderer), title, type)
            .takeIf(SearchResultIdentity::isValid)
    }

    private fun resolvedSearchResultType(
        renderer: JSONObject,
        forcedType: YouTubeMusicSearchResultType?
    ): YouTubeMusicSearchResultType? = forcedType ?: resolveSearchResultType(renderer)

    private fun buildSearchResult(
        renderer: JSONObject,
        identity: SearchResultIdentity
    ): YouTubeMusicSearchResult {
        val metadata = parseSearchMetadata(renderer, identity.type)
        val durationText = metadata.durationText.ifBlank { extractSearchDurationText(renderer) }
        return YouTubeMusicSearchResult(
            videoId = identity.videoId,
            title = identity.title,
            artist = metadata.artists.joinToString(" / ").ifBlank { "" },
            album = metadata.album,
            subtitle = extractColumnText(
                columns = renderer.optJSONArray("flexColumns"),
                index = 1,
                rendererKey = "musicResponsiveListItemFlexColumnRenderer"
            ),
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail")),
            durationText = durationText,
            durationMs = parseDurationTextToMs(durationText),
            type = identity.type
        )
    }

    private fun resolveSearchResultType(renderer: JSONObject): YouTubeMusicSearchResultType? {
        return searchTypeFromMusicVideoType(musicVideoType(renderer))
            ?: searchTypeFromLabel(extractSearchMetadataParts(renderer).firstOrNull().orEmpty())
    }

    private fun musicVideoType(renderer: JSONObject): String {
        return firstNonBlank(
            renderer.objectPath(
                "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                "watchEndpointMusicConfig"
            )?.optString("musicVideoType"),
            renderer.objectPath(
                "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                "watchEndpointMusicConfig"
            )?.optString("musicVideoType")
        ).uppercase(Locale.US)
    }

    private fun searchTypeFromMusicVideoType(value: String): YouTubeMusicSearchResultType? {
        return when {
            value.contains("ATV") -> YouTubeMusicSearchResultType.Song
            value.contains("OMV") || value.contains("UGC") -> YouTubeMusicSearchResultType.Video
            else -> null
        }
    }

    private fun searchTypeFromLabel(label: String): YouTubeMusicSearchResultType? {
        return when (normalizeSearchTypeToken(label)) {
            "song", "songs", "歌曲", "曲" -> YouTubeMusicSearchResultType.Song
            "video", "videos", "视频", "mv" -> YouTubeMusicSearchResultType.Video
            else -> null
        }
    }

    private fun YouTubeMusicHomeItem.isHomePlaylistCard(browseId: String): Boolean {
        if (browseId.isBlank()) {
            return false
        }
        return isMusicPlaylistBrowse(
            browseId = browseId,
            pageType = pageType
        )
    }

    private fun isMusicPlaylistBrowseEndpoint(
        browseId: String,
        browseEndpoint: JSONObject
    ): Boolean {
        val pageType = browseEndpoint.objectPath(
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig"
        )?.optString("pageType").orEmpty()
        return isMusicPlaylistBrowse(
            browseId = browseId,
            pageType = pageType
        )
    }

    private fun isMusicPlaylistBrowse(
        browseId: String,
        pageType: String
    ): Boolean {
        val normalizedPageType = pageType.uppercase(Locale.US)
        return when {
            normalizedPageType.contains("PLAYLIST") -> true
            normalizedPageType.isNotBlank() -> false
            else -> browseId.startsWith("VL")
        }
    }

    private fun extractSearchMetadataParts(renderer: JSONObject): List<String> {
        return extractTextParts(
            renderer.optJSONArray("flexColumns")
                ?.optJSONObject(1)
                .objectPath("musicResponsiveListItemFlexColumnRenderer", "text")
        )
    }

    private fun parseSearchMetadata(
        renderer: JSONObject,
        type: YouTubeMusicSearchResultType
    ): ParsedYouTubeMusicSearchMetadata {
        val runs = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(1)
            .objectPath("musicResponsiveListItemFlexColumnRenderer", "text")
            ?.optJSONArray("runs")
        return runs?.let { parseSearchMetadataRuns(it, type) }
            ?: fallbackSearchMetadata(renderer)
    }

    private fun fallbackSearchMetadata(renderer: JSONObject): ParsedYouTubeMusicSearchMetadata {
        return ParsedYouTubeMusicSearchMetadata(
            artists = extractSearchMetadataParts(renderer).filterNot(::looksLikeDurationText).take(1),
            durationText = extractSearchDurationText(renderer)
        )
    }

    private class SearchMetadataAccumulator {
        val artists = mutableListOf<String>()
        var album = ""
        var durationText = ""

        fun record(kind: SearchMetadataKind, text: String) {
            when (kind) {
                SearchMetadataKind.Duration -> durationText = text
                SearchMetadataKind.Album -> album = text
                SearchMetadataKind.Artist -> artists += text
                SearchMetadataKind.Ignore -> Unit
            }
        }

        fun toMetadata(): ParsedYouTubeMusicSearchMetadata {
            if (album.isBlank() && artists.size >= 2) {
                album = artists.last()
                artists.removeLastOrNull()
            }
            return ParsedYouTubeMusicSearchMetadata(artists, album, durationText)
        }
    }

    private enum class SearchMetadataKind { Duration, Album, Artist, Ignore }

    private fun parseSearchMetadataRuns(
        runs: JSONArray,
        type: YouTubeMusicSearchResultType
    ): ParsedYouTubeMusicSearchMetadata {
        return (0 until runs.length() step 2).asSequence()
            .mapNotNull { index -> runs.optJSONObject(index)?.let { index to it } }
            .fold(SearchMetadataAccumulator()) { accumulator, (index, run) ->
                acceptSearchMetadataRun(accumulator, run, type, index == 0 && runs.length() >= 3)
                accumulator
            }
            .toMetadata()
    }

    private fun acceptSearchMetadataRun(
        accumulator: SearchMetadataAccumulator,
        run: JSONObject,
        type: YouTubeMusicSearchResultType,
        canSkipType: Boolean
    ) {
        val text = run.optString("text", "").trim()
        if (shouldSkipSearchMetadataText(text, type, canSkipType)) return
        accumulator.record(searchMetadataKind(run, text), text)
    }

    private fun shouldSkipSearchMetadataText(
        text: String,
        type: YouTubeMusicSearchResultType,
        canSkipType: Boolean
    ): Boolean {
        return text.isBlank() || looksLikeSearchTypeLabel(text, type, canSkipType)
    }

    private fun searchMetadataKind(run: JSONObject, text: String): SearchMetadataKind {
        return when {
            looksLikeDurationText(text) -> SearchMetadataKind.Duration
            isAlbumRun(run) -> SearchMetadataKind.Album
            looksLikeSearchStatText(text) -> SearchMetadataKind.Ignore
            else -> SearchMetadataKind.Artist
        }
    }

    private fun isAlbumRun(run: JSONObject): Boolean {
        val browseId = run.objectPath("navigationEndpoint", "browseEndpoint")
            ?.optString("browseId").orEmpty()
        return browseId.startsWith("MPRE") || browseId.contains("release_detail")
    }

    private fun extractSearchDurationText(renderer: JSONObject): String {
        val fixedDuration = findDurationText(
            columns = renderer.optJSONArray("fixedColumns"),
            rendererKey = "musicResponsiveListItemFixedColumnRenderer"
        )
        if (fixedDuration.isNotBlank()) {
            return fixedDuration
        }
        return findDurationText(
            columns = renderer.optJSONArray("flexColumns"),
            rendererKey = "musicResponsiveListItemFlexColumnRenderer"
        )
    }

    private fun findDurationText(columns: JSONArray?, rendererKey: String): String {
        if (columns == null) {
            return ""
        }
        for (index in 0 until columns.length()) {
            val textNode = columns.optJSONObject(index)
                ?.optJSONObject(rendererKey)
                ?.optJSONObject("text")
            val durationText = extractDurationText(textNode)
            if (durationText.isNotBlank()) {
                return durationText
            }
        }
        return ""
    }

    private fun extractDurationText(node: JSONObject?): String {
        if (node == null) {
            return ""
        }
        val directText = extractText(node)
        if (looksLikeDurationText(directText)) {
            return directText
        }
        return extractTextParts(node)
            .firstOrNull(::looksLikeDurationText)
            .orEmpty()
    }

    private fun looksLikeDurationText(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.contains(':')) {
            return false
        }
        val parts = trimmed.split(':')
        return parts.isNotEmpty() && parts.all { it.trim().toLongOrNull() != null }
    }

    private fun looksLikeSearchStatText(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.US)
        return normalized.isNotBlank() && SEARCH_STAT_TOKENS.any { normalized.contains(it) }
    }

    private fun normalizeSearchTypeToken(token: String): String {
        return token.trim()
            .lowercase(Locale.US)
            .replace(" ", "")
    }

    private fun looksLikeSearchTypeLabel(
        text: String,
        type: YouTubeMusicSearchResultType,
        canSkip: Boolean
    ): Boolean {
        return canSkip && searchTypeFromLabel(text) == type
    }

    private fun looksLikeHomeSongTypeLabel(text: String): Boolean {
        return searchTypeFromLabel(text) != null
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun extractContinuationToken(renderer: JSONObject?): String? {
        return renderer?.optJSONArray("continuations").asObjectSequence()
            .map { it.objectPath("nextContinuationData")?.optString("continuation").orEmpty() }
            .firstOrNull(String::isNotBlank)
    }

    private fun extractContinuationTokenFromItems(contents: JSONArray?): String? {
        return contents.asObjectSequence()
            .map { it.objectPath("continuationItemRenderer", "continuationEndpoint", "continuationCommand")
                ?.optString("token").orEmpty() }
            .firstOrNull(String::isNotBlank)
    }

    private fun parseDataSyncId(dataSyncId: String): Pair<String, String> {
        return dataSyncId.takeIf(String::isNotBlank)?.let(::nonBlankDataSyncId) ?: ("" to "")
    }

    private fun nonBlankDataSyncId(dataSyncId: String): Pair<String, String> {
        val parts = dataSyncId.split("||", limit = 2)
        val first = parts[0]
        val second = parts.getOrNull(1).orEmpty()
        return if (second.isNotBlank()) {
            first to second
        } else {
            "" to first
        }
    }

    private fun extractColumnText(columns: JSONArray?, index: Int, rendererKey: String): String {
        return extractText(
            columns?.optJSONObject(index)
                ?.optJSONObject(rendererKey)
                ?.optJSONObject("text")
        )
    }

    private fun extractTextParts(node: JSONObject?): List<String> {
        return node?.optJSONArray("runs")?.let(::extractTextRunParts)
            ?: node?.optString("simpleText", "").orEmpty()
            .split('•', '·', '|')
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun extractTextRunParts(runs: JSONArray): List<String> {
        return (0 until runs.length()).asSequence()
            .mapNotNull(runs::optJSONObject)
            .map { it.optString("text").trim() }
            .filter(String::isNotBlank)
            .filterNot { text -> text.all { it in "•·|" } }
            .toList()
    }

    private fun extractText(node: JSONObject?): String {
        if (node == null) {
            return ""
        }
        val runs = node.optJSONArray("runs")
        if (runs != null) {
            return buildString {
                for (index in 0 until runs.length()) {
                    append(runs.optJSONObject(index)?.optString("text").orEmpty())
                }
            }.trim()
        }
        return node.optString("simpleText", "").trim()
    }

    private fun extractMusicThumbnailUrl(node: JSONObject?): String {
        return node?.let(::musicThumbnailUrl).orEmpty()
    }

    private fun musicThumbnailUrl(node: JSONObject): String {
        val container = thumbnailContainer(node) ?: return ""
        return upgradeYouTubeThumbnailUrl(lastThumbnailUrl(container))
    }

    private fun thumbnailContainer(node: JSONObject): JSONObject? {
        return when {
            node.has("musicThumbnailRenderer") -> node.optJSONObject("musicThumbnailRenderer")
            node.has("croppedSquareThumbnailRenderer") -> node.optJSONObject("croppedSquareThumbnailRenderer")
            else -> node
        }
    }

    private fun lastThumbnailUrl(container: JSONObject): String {
        val thumbnails = thumbnailArray(container) ?: return ""
        val last = thumbnails.optJSONObject(thumbnails.length() - 1) ?: return ""
        return last.optString("url")
    }

    private fun thumbnailArray(container: JSONObject): JSONArray? {
        return container.objectPath("thumbnail")?.optJSONArray("thumbnails")
            ?: container.optJSONArray("thumbnails")
    }

    internal fun parseLyricsBrowseId(root: JSONObject): String? {
        val tabs = root.objectPath(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer"
        )
            ?.optJSONArray("tabs")
        return tabs.asObjectSequence()
            .map { it.objectPath("tabRenderer", "endpoint", "browseEndpoint")?.optString("browseId").orEmpty() }
            .firstOrNull { it.startsWith("MPLYt") }
    }

    internal fun parseLyrics(root: JSONObject): YouTubeMusicLyrics? {
        val sections = root.objectPath("contents", "sectionListRenderer")
            ?.optJSONArray("contents")
        return sections.asObjectSequence()
            .mapNotNull { it.optJSONObject("musicDescriptionShelfRenderer") }
            .mapNotNull(::parseDescriptionLyrics)
            .firstOrNull()
    }

    private fun parseDescriptionLyrics(renderer: JSONObject): YouTubeMusicLyrics? {
        val lyrics = extractText(renderer.optJSONObject("description"))
        return lyrics.takeIf(String::isNotBlank)?.let {
            YouTubeMusicLyrics(lyrics = it, source = extractText(renderer.optJSONObject("footer")))
        }
    }

    private fun playlistIdFromBrowseId(browseId: String): String {
        return if (browseId.startsWith("VL")) {
            browseId.removePrefix("VL")
        } else {
            browseId
        }
    }
}

/**
 * 将 YouTube Music 缩略图 URL 升级为完整尺寸
 * YouTube 缩略图 URL 通常以 `=w60-h60-...` 结尾来限制尺寸
 * 此函数将其替换为 `=w1200-h1200` 以获取高清封面
 */
fun upgradeYouTubeThumbnailUrl(url: String): String {
    if (url.isBlank()) return url
    // lh3.googleusercontent.com 和 yt3.ggpht.com 样式的 URL 使用 = 参数来控制尺寸
    val sizeParamRegex = Regex("=w\\d+(-h\\d+)?(-[a-zA-Z0-9-]+)*$")
    return if (sizeParamRegex.containsMatchIn(url)) {
        url.replace(sizeParamRegex, "=w1200-h1200")
    } else if (url.contains("lh3.googleusercontent.com") || url.contains("yt3.ggpht.com")) {
        // 没有尺寸参数但属于 Google 图片服务的 URL, 附加尺寸参数
        if (url.contains('=')) url else "$url=w1200-h1200"
    } else {
        url
    }
}
