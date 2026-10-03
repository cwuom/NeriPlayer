package moe.ouom.neriplayer.platform.youtube.api.parser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicFollowedArtistsParserTest {
    @Test
    fun readsResponsiveArtistRowsAndShelfContinuation() {
        val page = YouTubeMusicParser.parseFollowedArtistsPage(
            browseRoot("musicShelfRenderer", "contents", responsiveArtist(), "next-artists")
        )!!

        val artist = page.artists.single()
        assertEquals("UCartist", artist.browseId)
        assertEquals("UCartist", artist.channelId)
        assertEquals("Demo Artist", artist.title)
        assertEquals("12K subscribers", artist.subtitle)
        assertEquals("https://example.com/artist.jpg", artist.coverUrl)
        assertEquals("next-artists", page.continuation)
    }

    @Test
    fun readsNestedGridInTwoColumnBrowse() {
        val root = JSONObject(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
              "content":{"sectionListRenderer":{"contents":[
                {"musicSortFilterButtonRenderer":{}},
                {"itemSectionRenderer":{"contents":[{"gridRenderer":{
                  "items":[${twoRowArtist()}]
                }}]}}
              ]}}
            }}]}}}
            """.trimIndent()
        )

        val page = YouTubeMusicParser.parseFollowedArtistsPage(root)!!

        assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
        assertNull(page.continuation)
    }

    @Test
    fun readsLegacyLibraryInSecondTab() {
        val root = libraryTabsRoot(
            """{"tabRenderer":{"content":{"musicNavigationButtonRenderer":{}}}}""",
            libraryTab(responsiveArtist(), "next-artists")
        )

        val page = YouTubeMusicParser.parseFollowedArtistsPage(root)!!

        assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
        assertEquals("next-artists", page.continuation)
    }

    @Test
    fun readsLegacyLibraryInThirdTabWithoutImportingDownloadsOrRecommendations() {
        val root = libraryTabsRoot(
            """{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
              "musicCarouselShelfRenderer":{"contents":[${twoRowArtist("UCrecommended")}]}
            }]}}}}""",
            libraryTab(twoRowArtist("UCdownloaded")),
            libraryTab(responsiveArtist(), "next-artists")
        )

        val page = YouTubeMusicParser.parseFollowedArtistsPage(root)!!

        assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
        assertEquals("next-artists", page.continuation)
    }

    @Test
    fun doesNotTreatRecommendationCarouselAsFollowedArtists() {
        val root = browseRoot("musicCarouselShelfRenderer", "contents", twoRowArtist("UCrecommended"))

        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(root))
        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(JSONObject(
            """{"continuationContents":{"musicCarouselShelfContinuation":{
              "contents":[${twoRowArtist("UCrecommended")}]
            }}}"""
        )))
    }

    @Test
    fun distinguishesLegacyEmptyShelfFromSignInAndMalformedLibraryTabs() {
        val emptyRoot = libraryTabsRoot("""{"tabRenderer":{}}""", libraryTab(""))
        val signInRoot = libraryTabsRoot(
            """{"tabRenderer":{}}""",
            """{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
              "messageRenderer":{"text":{"simpleText":"Sign in"}}
            }]}}}}"""
        )
        val malformedRoot = libraryTabsRoot(
            """{"tabRenderer":{}}""",
            """{"tabRenderer":{}}""",
            """{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
              "musicShelfRenderer":{}
            }]}}}}"""
        )

        assertEquals(emptyList<String>(), YouTubeMusicParser.parseFollowedArtistsPage(emptyRoot)!!.artists.map { it.browseId })
        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(signInRoot))
        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(malformedRoot))
    }

    @Test
    fun readsDirectSectionListPlaylistShelf() {
        val root = JSONObject(
            """
            {"contents":{"sectionListRenderer":{"contents":[{
              "musicPlaylistShelfRenderer":{"contents":[${responsiveArtist()}]}
            }]}}}
            """.trimIndent()
        )

        assertEquals(
            listOf("UCartist"),
            YouTubeMusicParser.parseFollowedArtistsPage(root)!!.artists.map { it.browseId }
        )
    }

    @Test
    fun readsRendererContinuationVariants() {
        listOf(
            "gridContinuation" to "items",
            "musicShelfContinuation" to "contents",
            "musicPlaylistShelfContinuation" to "contents"
        ).forEach { (renderer, contentKey) ->
            val root = JSONObject(
                """
                {"continuationContents":{"$renderer":{
                  "$contentKey":[${twoRowArtist()}],
                  "continuations":[{"nextContinuationData":{"continuation":"next-artists"}}]
                }}}
                """.trimIndent()
            )

            val page = YouTubeMusicParser.parseFollowedArtistsPage(root)!!

            assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
            assertEquals("next-artists", page.continuation)
        }
    }

    @Test
    fun readsAppendedContinuationItems() {
        val root = JSONObject(
            """
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{
              "continuationItems":[${responsiveArtist()}, {
                "continuationItemRenderer":{"continuationEndpoint":{
                  "continuationCommand":{"token":"next-artists"}
                }}
              }]
            }}]}
            """.trimIndent()
        )

        val page = YouTubeMusicParser.parseFollowedArtistsPage(root)!!

        assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
        assertEquals("next-artists", page.continuation)
    }

    @Test
    fun distinguishesEmptyLibraryFromUnknownOrSignInResponse() {
        val emptyPage = YouTubeMusicParser.parseFollowedArtistsPage(
            browseRoot("musicShelfRenderer", "contents", "")
        )!!

        assertEquals(emptyList<String>(), emptyPage.artists.map { it.browseId })
        assertNull(emptyPage.continuation)
        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(JSONObject("{}")))
        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(JSONObject(
            """{"contents":{"sectionListRenderer":{"contents":[{"messageRenderer":{
              "text":{"simpleText":"Sign in"}
            }}]}}}"""
        )))
        assertNull(YouTubeMusicParser.parseFollowedArtistsPage(JSONObject(
            """{"continuationContents":{"musicShelfContinuation":{}}}"""
        )))
    }

    @Test
    fun excludesNonCreatorsAndDeduplicatesByBrowseId() {
        val page = YouTubeMusicParser.parseFollowedArtistsPage(
            browseRoot(
                "gridRenderer", "items",
                listOf(
                    twoRowArtist(),
                    twoRowArtist(title = "Duplicate"),
                    twoRowArtist(browseId = "UCother"),
                    twoRowArtist(browseId = "MPREalbum", pageType = "MUSIC_PAGE_TYPE_ALBUM"),
                    twoRowArtist(browseId = "VLplaylist", pageType = "MUSIC_PAGE_TYPE_PLAYLIST"),
                    """{"musicTwoRowItemRenderer":{
                      "title":{"runs":[{"text":"Song","navigationEndpoint":{
                        "browseEndpoint":{"browseId":"UCsongAuthor"}
                      }}]},
                      "navigationEndpoint":{"watchEndpoint":{"videoId":"song-video"}}
                    }}"""
                ).joinToString(",")
            )
        )!!

        assertEquals(listOf("UCartist", "UCother"), page.artists.map { it.browseId })
        assertEquals(listOf("Demo Artist", "Demo Artist"), page.artists.map { it.title })
    }

    @Test
    fun ignoresRowsWithoutNavigableCreatorIdentity() {
        val rowWithoutIdentity = """
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{
                "text":{"simpleText":"Unavailable Artist"}
              }}]
            }}
        """.trimIndent()
        val page = YouTubeMusicParser.parseFollowedArtistsPage(
            browseRoot("musicShelfRenderer", "contents", "$rowWithoutIdentity,${responsiveArtist()}")
        )!!

        assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
    }

    @Test
    fun doesNotImportSongAuthorsFromPlaylistMetadataAsFollowedArtists() {
        val songWithAuthorLink = """
            {"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"song-video"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{
                  "text":"Song Author","navigationEndpoint":{"browseEndpoint":{"browseId":"UCsongAuthor"}}
                }]}}}
              ]
            }}
        """.trimIndent()
        val page = YouTubeMusicParser.parseFollowedArtistsPage(
            browseRoot("musicShelfRenderer", "contents", "$songWithAuthorLink,${responsiveArtist()}")
        )!!

        assertEquals(listOf("UCartist"), page.artists.map { it.browseId })
    }

    @Test
    fun preservesContinuationOnAnEmptyPage() {
        val page = YouTubeMusicParser.parseFollowedArtistsPage(
            browseRoot("musicShelfRenderer", "contents", "", "next-artists")
        )!!

        assertEquals(emptyList<String>(), page.artists.map { it.browseId })
        assertEquals("next-artists", page.continuation)
    }

    private fun browseRoot(
        renderer: String,
        contentKey: String,
        items: String,
        continuation: String? = null
    ): JSONObject {
        val next = continuation?.let {
            ",\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"$it\"}}]"
        }.orEmpty()
        return JSONObject(
            """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
              "content":{"sectionListRenderer":{"contents":[{
                "$renderer":{"$contentKey":[$items]$next}
              }]}}
            }}]}}}
            """.trimIndent()
        )
    }

    private fun libraryTabsRoot(vararg tabs: String): JSONObject = JSONObject(
        """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[${tabs.joinToString(",")}]}}}"""
    )

    private fun libraryTab(items: String, continuation: String? = null): String {
        val next = continuation?.let {
            ",\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"$it\"}}]"
        }.orEmpty()
        return """
            {"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
              "itemSectionRenderer":{"contents":[{"musicShelfRenderer":{
                "contents":[$items]$next
              }}]}
            }]}}}}
        """.trimIndent()
    }

    private fun twoRowArtist(
        browseId: String = "UCartist",
        title: String = "Demo Artist",
        pageType: String = "MUSIC_PAGE_TYPE_ARTIST"
    ): String = """
        {"musicTwoRowItemRenderer":{
          "title":{"simpleText":"$title"},
          "subtitle":{"simpleText":"12K subscribers"},
          "navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
              "pageType":"$pageType"
            }}
          }}
        }}
    """.trimIndent()

    private fun responsiveArtist(): String = """
        {"musicResponsiveListItemRenderer":{
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{
              "text":"Demo Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}
            }]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"12K subscribers"}}}
          ],
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{
            "url":"https://example.com/artist.jpg"
          }]}}}
        }}
    """.trimIndent()
}
