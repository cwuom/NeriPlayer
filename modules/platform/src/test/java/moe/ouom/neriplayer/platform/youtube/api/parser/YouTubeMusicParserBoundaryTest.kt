package moe.ouom.neriplayer.platform.youtube.api.parser

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicParserBoundaryTest {
    @Test
    fun libraryGridRejectsCardsWithoutBrowseIdOrTitle() {
        fun card(browseId: String, title: String) = obj(
            "musicTwoRowItemRenderer" to obj(
                "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to browseId)),
                "title" to obj("simpleText" to title)
            )
        )
        val root = browseRoot(
            obj("gridRenderer" to obj("items" to array(
                card(" ", "Missing ID"),
                card("VLuntitled", " "),
                card("VLvalid", "Valid playlist")
            )))
        )

        val playlist = YouTubeMusicParser.parseLibraryPlaylists(root).single()
        assertEquals("VLvalid", playlist.browseId)
        assertEquals("Valid playlist", playlist.title)
    }

    @Test
    fun playlistTracksRequireIdentityAndUseFlexDurationWhenFixedDurationIsAbsent() {
        fun track(videoId: String?, title: String) = obj(
            "musicResponsiveListItemRenderer" to obj(
                "playlistItemData" to videoId?.let { obj("videoId" to it) },
                "flexColumns" to array(
                    flexColumn(title),
                    flexColumn("Demo Artist"),
                    flexColumn("3:15")
                )
            )
        )
        val root = obj("continuationContents" to obj(
            "musicPlaylistShelfContinuation" to obj("contents" to array(
                track(null, "No ID"), track("missing-title", " "), track("song-id", "Song")
            ))
        ))

        val parsed = YouTubeMusicParser.parsePlaylistTracks(root).single()
        assertEquals("song-id", parsed.videoId)
        assertEquals("3:15", parsed.durationText)
        assertEquals(195_000L, parsed.durationMs)
    }

    @Test
    fun homeShelvesSkipAbsentHeadersAndEmptyItemsWhileKeepingBrowseRoutes() {
        val browsedSong = obj("musicTwoRowItemRenderer" to obj(
            "title" to obj("simpleText" to "Browsable"),
            "navigationEndpoint" to obj("browseEndpoint" to obj(
                "browseId" to "VLbrowse",
                "browseEndpointContextSupportedConfigs" to obj(
                    "browseEndpointContextMusicConfig" to obj("pageType" to "MUSIC_PAGE_TYPE_PLAYLIST")
                )
            )),
            "thumbnailRenderer" to obj("musicThumbnailRenderer" to obj(
                "thumbnail" to obj("thumbnails" to array(obj("url" to "https://example.com/cover")))
            ))
        ))
        val root = obj("continuationContents" to obj("sectionListContinuation" to obj(
            "contents" to array(
                obj("musicCarouselShelfRenderer" to obj("contents" to array(browsedSong))),
                obj("musicCarouselShelfRenderer" to obj(
                    "header" to obj("musicCarouselShelfBasicHeaderRenderer" to obj()),
                    "contents" to array()
                )),
                obj("musicCarouselShelfRenderer" to obj(
                    "header" to obj("musicCarouselShelfBasicHeaderRenderer" to obj(
                        "title" to obj("simpleText" to "For you")
                    )),
                    "contents" to array(browsedSong, obj("musicTwoRowItemRenderer" to obj(
                        "title" to obj("simpleText" to "Without route")
                    )))
                ))
            )
        )))

        val shelf = YouTubeMusicParser.parseHomeShelfPages(root).single()
        assertEquals("For you", shelf.title)
        assertEquals(listOf("Browsable", "Without route"), shelf.items.map { it.title })
        assertEquals("VLbrowse", shelf.items.first().browseId)
        assertEquals("MUSIC_PAGE_TYPE_PLAYLIST", shelf.items.first().pageType)
        assertEquals("https://example.com/cover", shelf.items.first().coverUrl)
        assertEquals("", shelf.items.last().browseId)
    }

    @Test
    fun playlistDetailPreservesMissingShelfAndBlankIdFallbackRules() {
        val empty = YouTubeMusicParser.parsePlaylistDetail(JSONObject(), "VLbrowse", "Fallback", "", "")
        assertEquals("", empty.playlistId)
        val blank = obj("continuationContents" to obj(
            "musicPlaylistShelfContinuation" to obj("playlistId" to "")
        ))
        val explicit = obj("continuationContents" to obj(
            "musicPlaylistShelfContinuation" to obj("playlistId" to "explicit")
        ))
        assertEquals("browse", YouTubeMusicParser.parsePlaylistDetail(blank, "VLbrowse", "", "", "").playlistId)
        assertEquals("explicit", YouTubeMusicParser.parsePlaylistDetail(explicit, "VLbrowse", "", "", "").playlistId)
    }

    @Test
    fun searchRowsKeepArtistAlbumAndDurationWhileRejectingIncompleteIdentity() {
        fun run(text: String, browseId: String? = null) = obj(
            "text" to text,
            "navigationEndpoint" to browseId?.let { obj("browseEndpoint" to obj("browseId" to it)) }
        )
        fun row(videoId: String?, title: String, metadata: JSONObject) = obj(
            "musicResponsiveListItemRenderer" to obj(
                "playlistItemData" to videoId?.let { obj("videoId" to it) },
                "flexColumns" to array(flexColumn(title),
                    obj("musicResponsiveListItemFlexColumnRenderer" to obj("text" to metadata)))
            )
        )
        val metadataRuns = obj("runs" to array(
            run("Song"), run(" • "), run("Artist"), run(" • "),
            run("Album", "MPREalbum"), run(" • "), run("3:20")
        ))
        val root = searchRoot(
            row(null, "Missing ID", metadataRuns),
            row("missing-title", " ", metadataRuns),
            row("complete", "Complete", metadataRuns),
            row("simple", "Simple", obj("simpleText" to "Solo Artist • 12M views")),
            row("empty-metadata", "Without metadata", obj())
        )

        val results = YouTubeMusicParser.parseSongSearchResults(root)
        assertEquals(listOf("complete", "simple", "empty-metadata"), results.map { it.videoId })
        assertEquals("Artist", results.first().artist)
        assertEquals("Album", results.first().album)
        assertEquals(200_000L, results.first().durationMs)
        assertEquals("Solo Artist", results[1].artist)
        assertTrue(results[1].album.isEmpty())
        assertTrue(results.last().artist.isEmpty())
    }

    @Test
    fun creatorSearchFallsBackToSubscribeMenuForNonChannelBrowseId() {
        val menu = obj("menuRenderer" to obj("items" to array(
            obj("menuServiceItemRenderer" to obj()),
            obj("toggleMenuServiceItemRenderer" to obj(
                "defaultServiceEndpoint" to obj("subscribeEndpoint" to obj(
                    "channelIds" to array("UCsubscribed")
                ))
            ))
        )))
        fun creator(title: String) = obj("musicTwoRowItemRenderer" to obj(
            "title" to obj("simpleText" to title),
            "subtitle" to obj("simpleText" to "Artist"),
            "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to "MPREcreator")),
            "menu" to menu
        ))

        val result = YouTubeMusicParser.parseCreatorSearchResults(
            searchRoot(creator(" "), creator("Demo Creator"))
        ).single()
        assertEquals("MPREcreator", result.browseId)
        assertEquals("UCsubscribed", result.channelId)
        assertEquals("Demo Creator", result.title)
    }

    @Test
    fun creatorCarouselKeepsAlbumAndSongRoutesAndSkipsInvalidCards() {
        val album = obj("musicTwoRowItemRenderer" to obj(
            "title" to obj("simpleText" to "Album"),
            "navigationEndpoint" to obj("browseEndpoint" to obj(
                "browseId" to "MPREalbum",
                "browseEndpointContextSupportedConfigs" to obj(
                    "browseEndpointContextMusicConfig" to obj("pageType" to "MUSIC_PAGE_TYPE_ALBUM")
                )
            ))
        ))
        val song = obj("musicResponsiveListItemRenderer" to obj(
            "playlistItemData" to obj("videoId" to "creator-song"),
            "flexColumns" to array(flexColumn("Creator song"), flexColumn("Artist"))
        ))
        val video = obj("musicTwoRowItemRenderer" to obj(
            "title" to obj("simpleText" to "Video"),
            "navigationEndpoint" to obj("watchEndpoint" to obj("videoId" to "creator-video"))
        ))
        val playlist = obj("musicTwoRowItemRenderer" to obj(
            "title" to obj("simpleText" to "Playlist"),
            "navigationEndpoint" to obj("browseEndpoint" to obj("browseId" to "VLplaylist"))
        ))
        val invalidSong = obj("musicResponsiveListItemRenderer" to obj(
            "flexColumns" to array(flexColumn("No route"))
        ))
        val invalidTitle = obj("musicResponsiveListItemRenderer" to obj(
            "playlistItemData" to obj("videoId" to "hidden"),
            "flexColumns" to array(flexColumn(" "))
        ))
        val invalidBrowse = obj("musicTwoRowItemRenderer" to obj(
            "title" to obj("simpleText" to "No route")
        ))
        val carousel = obj("musicCarouselShelfRenderer" to obj(
            "header" to obj("musicCarouselShelfBasicHeaderRenderer" to obj(
                "title" to obj("simpleText" to "Creator picks")
            )),
            "contents" to array(album, song, video, playlist, invalidSong, invalidTitle, invalidBrowse)
        ))
        val root = obj("contents" to obj("sectionListRenderer" to obj(
            "contents" to array(
                obj("musicCarouselShelfRenderer" to obj("contents" to array(album))),
                carousel
            )
        )))
        val fallback = YouTubeMusicCreatorSummary("UCcreator", "Creator", "", "")

        val section = YouTubeMusicParser.parseCreatorDetail(root, fallback).sections.single()
        assertEquals("Creator picks", section.title)
        assertEquals(listOf("Album", "Creator song", "Video", "Playlist"), section.items.map { it.title })
        assertEquals(YouTubeMusicCreatorItemType.Album, section.items.first().type)
        assertEquals(YouTubeMusicCreatorItemType.Song, section.items[1].type)
        assertEquals(YouTubeMusicCreatorItemType.Video, section.items[2].type)
        assertEquals(YouTubeMusicCreatorItemType.Playlist, section.items.last().type)
    }

    @Test
    fun bootstrapDataSyncUsesSingleSessionWhenDelegationIsAbsent() {
        listOf("single-session", "delegated-session||").forEach { dataSyncId ->
            val bootstrap = YouTubeMusicParser.parseBootstrapConfig(
                html = """
                    "INNERTUBE_API_KEY":"api-key"
                    "INNERTUBE_CLIENT_VERSION":"1.0"
                    "VISITOR_DATA":"visitor"
                    "DATASYNC_ID":"$dataSyncId"
                """.trimIndent(),
                cookieHeader = "",
                userAgent = "BoundaryTest/1.0"
            )
            assertEquals(dataSyncId.substringBefore("||"), bootstrap.userSessionId)
        }
    }

    @Test
    fun creatorCarouselPageAndGridContinuationPreserveItems() {
        val item = obj("musicResponsiveListItemRenderer" to obj(
            "playlistItemData" to obj("videoId" to "song"),
            "flexColumns" to array(flexColumn("Song"), flexColumn("Artist"))
        ))
        val pageRoot = browseRoot(obj("musicCarouselShelfRenderer" to obj(
            "header" to obj("musicCarouselShelfBasicHeaderRenderer" to obj(
                "title" to obj("simpleText" to "Carousel songs")
            )),
            "contents" to array(item)
        )))
        val page = YouTubeMusicParser.parseCreatorItemsPage(pageRoot, "Fallback")
        assertEquals("Carousel songs", page.title)
        assertEquals("song", page.items.single().videoId)

        val continuationRoot = obj("continuationContents" to obj(
            "gridContinuation" to obj("items" to array(item))
        ))
        val continuation = YouTubeMusicParser.parseCreatorItemsContinuation(continuationRoot)
        assertEquals("song", continuation.items.single().videoId)
    }

    private fun browseRoot(vararg sections: JSONObject): JSONObject = obj(
        "contents" to obj("singleColumnBrowseResultsRenderer" to obj("tabs" to array(
            obj("tabRenderer" to obj("content" to obj("sectionListRenderer" to obj(
                "contents" to array(*sections)
            ))))
        )))
    )

    private fun searchRoot(vararg items: JSONObject): JSONObject = obj(
        "contents" to obj("tabbedSearchResultsRenderer" to obj("tabs" to array(
            obj("tabRenderer" to obj("content" to obj("sectionListRenderer" to obj(
                "contents" to array(obj("musicShelfRenderer" to obj("contents" to array(*items))))
            ))))
        )))
    )

    private fun flexColumn(text: String): JSONObject = obj(
        "musicResponsiveListItemFlexColumnRenderer" to obj("text" to obj("simpleText" to text))
    )

    private fun obj(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        fields.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    private fun array(vararg values: Any?): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }
}
