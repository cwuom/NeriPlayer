package moe.ouom.neriplayer.core.api.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicParserTest {

    @Test
    fun parsePlaylistTrackCount_readsSecondSubtitleFromResponsiveHeader() {
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicResponsiveHeaderRenderer": {
                                  "title": { "simpleText": "Liked Music" },
                                  "secondSubtitle": {
                                    "runs": [
                                      { "text": "37 songs" },
                                      { "text": " - " },
                                      { "text": "over 5 hours" }
                                    ]
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(37, YouTubeMusicParser.parsePlaylistTrackCount(root))
    }

    @Test
    fun parsePlaylistTracks_findsShelfRendererBeyondFirstSection() {
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "messageRenderer": {
                            "text": { "simpleText": "ignored" }
                          }
                        },
                        {
                          "musicPlaylistShelfRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "overlay": {
                                    "musicItemThumbnailOverlayRenderer": {
                                      "content": {
                                        "musicPlayButtonRenderer": {
                                          "playNavigationEndpoint": {
                                            "watchEndpoint": {
                                              "videoId": "video-1"
                                            }
                                          }
                                        }
                                      }
                                    }
                                  },
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Song A" }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Artist A" }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Album A" }
                                      }
                                    }
                                  ],
                                  "fixedColumns": [
                                    {
                                      "musicResponsiveListItemFixedColumnRenderer": {
                                        "text": { "simpleText": "3:21" }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val tracks = YouTubeMusicParser.parsePlaylistTracks(root)

        assertEquals(1, tracks.size)
        assertEquals("video-1", tracks.first().videoId)
        assertEquals("Song A", tracks.first().title)
        assertEquals("Artist A", tracks.first().artist)
        assertEquals("Album A", tracks.first().album)
        assertEquals(201_000L, tracks.first().durationMs)
    }

    @Test
    fun parsePlaylistTracks_fallsBackWhenOverlayVideoIdIsMissing() {
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicPlaylistShelfRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": {
                                    "videoId": "video-from-playlist-item-data"
                                  },
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Song A" }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Artist A" }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Album A" }
                                      }
                                    }
                                  ],
                                  "fixedColumns": [
                                    {
                                      "musicResponsiveListItemFixedColumnRenderer": {
                                        "text": { "simpleText": "3:21" }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "menu": {
                                    "menuRenderer": {
                                      "items": [
                                        {
                                          "menuServiceItemRenderer": {
                                            "serviceEndpoint": {
                                              "queueAddEndpoint": {
                                                "queueTarget": {
                                                  "videoId": "video-from-menu"
                                                }
                                              }
                                            }
                                          }
                                        }
                                      ]
                                    }
                                  },
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": {
                                          "runs": [
                                            {
                                              "text": "Song B",
                                              "navigationEndpoint": {
                                                "watchEndpoint": {
                                                  "videoId": "video-from-title-run"
                                                }
                                              }
                                            }
                                          ]
                                        }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "simpleText": "Artist B" }
                                      }
                                    }
                                  ],
                                  "fixedColumns": [
                                    {
                                      "musicResponsiveListItemFixedColumnRenderer": {
                                        "text": { "simpleText": "4:05" }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val tracks = YouTubeMusicParser.parsePlaylistTracks(root)

        assertEquals(2, tracks.size)
        assertEquals("video-from-playlist-item-data", tracks[0].videoId)
        assertEquals("Song A", tracks[0].title)
        assertEquals("video-from-title-run", tracks[1].videoId)
        assertEquals("Song B", tracks[1].title)
    }

    @Test
    fun extractPlaylistContinuation_readsShelfTokenBeyondFirstSection() {
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "messageRenderer": {
                            "text": { "simpleText": "ignored" }
                          }
                        },
                        {
                          "musicPlaylistShelfRenderer": {
                            "continuations": [
                              {
                                "nextContinuationData": {
                                  "continuation": "token-123"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("token-123", YouTubeMusicParser.extractPlaylistContinuation(root))
    }

    @Test
    fun parsePlaylistTracks_findsShelfRendererInPrimarySections() {
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicResponsiveHeaderRenderer": {
                                  "title": { "simpleText": "Header" }
                                }
                              },
                              {
                                "musicPlaylistShelfRenderer": {
                                  "playlistId": "PL-primary",
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "playlistItemData": {
                                          "videoId": "primary-video"
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "simpleText": "Primary Song" }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val detail = YouTubeMusicParser.parsePlaylistDetail(
            root = root,
            browseId = "VLPL-primary",
            fallbackTitle = "",
            fallbackSubtitle = "",
            fallbackCoverUrl = ""
        )

        assertEquals("PL-primary", detail.playlistId)
        assertEquals(1, detail.tracks.size)
        assertEquals("primary-video", detail.tracks.first().videoId)
        assertEquals("Primary Song", detail.tracks.first().title)
    }

    @Test
    fun parsePlaylistDetail_supportsTopLevelResponsiveHeader() {
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicResponsiveHeaderRenderer": {
                                  "title": { "simpleText": "Liked Music" },
                                  "subtitle": { "simpleText": "Autoplay playlist" },
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [
                                          { "url": "https://example.com/liked.jpg" }
                                        ]
                                      }
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ],
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicPlaylistShelfRenderer": {
                            "playlistId": "LM",
                            "contents": []
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val detail = YouTubeMusicParser.parsePlaylistDetail(
            root = root,
            browseId = "VLLM",
            fallbackTitle = "",
            fallbackSubtitle = "",
            fallbackCoverUrl = ""
        )

        assertEquals("LM", detail.playlistId)
        assertEquals("Liked Music", detail.title)
        assertEquals("Autoplay playlist", detail.subtitle)
        assertTrue(detail.coverUrl.endsWith("liked.jpg"))
    }

    @Test
    fun parseHomeShelfPages_readsSongItemsAndShelfContinuation() {
        val root = JSONObject(
            """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicCarouselShelfRenderer": {
                                  "header": {
                                    "musicCarouselShelfBasicHeaderRenderer": {
                                      "title": { "simpleText": "再听一遍" }
                                    }
                                  },
                                  "contents": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "simpleText": "Song A" },
                                        "subtitle": { "simpleText": "Artist A • Album A" },
                                        "navigationEndpoint": {
                                          "watchEndpoint": { "videoId": "video-a" }
                                        }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "playlistItemData": { "videoId": "video-b" },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "simpleText": "Song B" }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "simpleText": "Artist B" }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ],
                                  "continuations": [
                                    {
                                      "nextContinuationData": {
                                        "continuation": "home-shelf-token"
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val shelves = YouTubeMusicParser.parseHomeShelfPages(root)

        assertEquals(1, shelves.size)
        assertEquals("再听一遍", shelves.first().title)
        assertEquals("home-shelf-token", shelves.first().continuation)
        assertEquals(2, shelves.first().items.size)
        assertEquals("video-a", shelves.first().items[0].videoId)
        assertEquals("video-b", shelves.first().items[1].videoId)
    }

    @Test
    fun parseHomeShelfPages_readsBrowsePageTypeForHomeCards() {
        val root = JSONObject(
            """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicCarouselShelfRenderer": {
                                  "header": {
                                    "musicCarouselShelfBasicHeaderRenderer": {
                                      "title": { "simpleText": "为你推荐" }
                                    }
                                  },
                                  "contents": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "simpleText": "Playlist A" },
                                        "subtitle": { "simpleText": "42 songs" },
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "VLPL-home-playlist",
                                            "browseEndpointContextSupportedConfigs": {
                                              "browseEndpointContextMusicConfig": {
                                                "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                              }
                                            }
                                          }
                                        }
                                      }
                                    },
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "simpleText": "Artist A" },
                                        "subtitle": { "simpleText": "Artist • 1M subscribers" },
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "UC-artist-home",
                                            "browseEndpointContextSupportedConfigs": {
                                              "browseEndpointContextMusicConfig": {
                                                "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val shelves = YouTubeMusicParser.parseHomeShelfPages(root)

        assertEquals(1, shelves.size)
        assertEquals("MUSIC_PAGE_TYPE_PLAYLIST", shelves.first().items[0].pageType)
        assertEquals("MUSIC_PAGE_TYPE_ARTIST", shelves.first().items[1].pageType)
    }

    @Test
    fun parseHomeSongMetadata_skipsSongTypeLabelAndDuration() {
        val metadata = YouTubeMusicParser.parseHomeSongMetadata(
            subtitle = "歌曲 • 陈芳语 • 爱你 • 3:27",
            fallbackAlbum = "猜你喜欢"
        )

        assertEquals("陈芳语", metadata.artist)
        assertEquals("爱你", metadata.album)
    }

    @Test
    fun parseHomeSongMetadata_preservesNormalArtistAndAlbum() {
        val metadata = YouTubeMusicParser.parseHomeSongMetadata(
            subtitle = "Artist A • Album A",
            fallbackAlbum = "每日发现"
        )

        assertEquals("Artist A", metadata.artist)
        assertEquals("Album A", metadata.album)
    }

    @Test
    fun parseHomeSongMetadata_dropsVideoStatsAndFallsBackAlbum() {
        val metadata = YouTubeMusicParser.parseHomeSongMetadata(
            subtitle = "视频 • Owner A • 1M views",
            fallbackAlbum = "翻唱与混音"
        )

        assertEquals("Owner A", metadata.artist)
        assertEquals("翻唱与混音", metadata.album)
    }

    @Test
    fun extractHomeContinuationAndShelfItems_supportSectionContinuationPayload() {
        val root = JSONObject(
            """
            {
              "continuationContents": {
                "sectionListContinuation": {
                  "contents": [],
                  "continuations": [
                    {
                      "nextContinuationData": {
                        "continuation": "home-next-token"
                      }
                    }
                  ]
                },
                "musicShelfContinuation": {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "video-c" },
                        "flexColumns": [
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "simpleText": "Song C" }
                            }
                          },
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "simpleText": "Artist C" }
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "continuations": [
                    {
                      "nextContinuationData": {
                        "continuation": "home-shelf-next-token"
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("home-next-token", YouTubeMusicParser.extractHomeContinuation(root))
        assertEquals(1, YouTubeMusicParser.parseHomeShelfContinuationItems(root).size)
        assertEquals("video-c", YouTubeMusicParser.parseHomeShelfContinuationItems(root).first().videoId)
        assertEquals("home-shelf-next-token", YouTubeMusicParser.extractHomeShelfContinuation(root))
    }

    @Test
    fun hasSearchSectionList_detectsSearchTabRenderer() {
        val root = createMixedSearchResultsRoot()

        assertTrue(YouTubeMusicParser.hasSearchSectionList(root))
        assertTrue(!YouTubeMusicParser.hasSearchSectionList(JSONObject()))
    }

    @Test
    fun parseSearchResults_extractsPlayableSongAndVideoFromMixedShelf() {
        val results = YouTubeMusicParser.parseSearchResults(createMixedSearchResultsRoot())

        assertEquals(2, results.size)

        val song = results[0]
        assertEquals("song-video-id", song.videoId)
        assertEquals("Song Result", song.title)
        assertEquals("Artist A", song.artist)
        assertEquals("Album A", song.album)
        assertEquals("Song • Artist A • Album A", song.subtitle)
        assertEquals("https://i.ytimg.com/vi/song-video-id/hqdefault.jpg", song.coverUrl)
        assertEquals("3:21", song.durationText)
        assertEquals(201_000L, song.durationMs)
        assertEquals(YouTubeMusicSearchResultType.Song, song.type)

        val video = results[1]
        assertEquals("video-video-id", video.videoId)
        assertEquals("Video Result", video.title)
        assertEquals("Artist B", video.artist)
        assertEquals("", video.album)
        assertEquals("Video • Artist B", video.subtitle)
        assertEquals("https://i.ytimg.com/vi/video-video-id/hqdefault.jpg", video.coverUrl)
        assertEquals("4:05", video.durationText)
        assertEquals(245_000L, video.durationMs)
        assertEquals(YouTubeMusicSearchResultType.Video, video.type)
    }

    @Test
    fun parseSearchResults_filtersNonPlayableEntriesAndHonorsLimit() {
        val results = YouTubeMusicParser.parseSearchResults(
            root = createMixedSearchResultsRoot(),
            limit = 1
        )

        assertEquals(1, results.size)
        assertEquals("song-video-id", results.single().videoId)
        assertTrue(results.none { it.title == "Artist Result" })
        assertTrue(results.none { it.title == "Unavailable Song" })
    }

    @Test
    fun hasSongSearchShelf_andParseSongSearchResults_readFilteredSongShelf() {
        val root = createSongSearchResultsRoot()

        assertTrue(YouTubeMusicParser.hasSongSearchShelf(root))
        assertTrue(!YouTubeMusicParser.hasSongSearchShelf(JSONObject()))

        val results = YouTubeMusicParser.parseSongSearchResults(root)

        assertEquals(1, results.size)
        val song = results.single()
        assertEquals("song-qt", song.videoId)
        assertEquals("晴天", song.title)
        assertEquals("周杰倫", song.artist)
        assertEquals("葉惠美", song.album)
        assertEquals("周杰倫 • 葉惠美 • 4:30", song.subtitle)
        assertEquals("4:30", song.durationText)
        assertEquals(270_000L, song.durationMs)
        assertEquals(YouTubeMusicSearchResultType.Song, song.type)
    }

    @Test
    fun extractSearchContinuation_readsContinuationFromFilteredSongShelf() {
        assertEquals(
            "song-shelf-token",
            YouTubeMusicParser.extractSearchContinuation(createSongSearchResultsRoot())
        )
    }

    @Test
    fun hasSongSearchShelf_andParseSongSearchResults_supportWrappedItemSectionShelf() {
        val root = createWrappedSongSearchResultsRoot()

        assertTrue(YouTubeMusicParser.hasSongSearchShelf(root))

        val results = YouTubeMusicParser.parseSongSearchResults(root)

        assertEquals(1, results.size)
        val song = results.single()
        assertEquals("song-qt", song.videoId)
        assertEquals("晴天", song.title)
        assertEquals("周杰倫", song.artist)
        assertEquals("葉惠美", song.album)
        assertEquals("周杰倫 • 葉惠美 • 4:30", song.subtitle)
        assertEquals("4:30", song.durationText)
        assertEquals(270_000L, song.durationMs)
        assertEquals("wrapped-song-shelf-token", YouTubeMusicParser.extractSearchContinuation(root))
    }

    @Test
    fun parseSongSearchResults_supportsMusicShelfContinuation() {
        val root = createSongSearchContinuationRoot()

        val results = YouTubeMusicParser.parseSongSearchResults(root)

        assertEquals(1, results.size)
        val song = results.single()
        assertEquals("song-anjing", song.videoId)
        assertEquals("安静", song.title)
        assertEquals("周杰倫", song.artist)
        assertEquals("范特西", song.album)
        assertEquals("5:34", song.durationText)
        assertEquals(334_000L, song.durationMs)
        assertEquals("song-shelf-next-token", YouTubeMusicParser.extractSearchContinuation(root))
    }

    private fun createMixedSearchResultsRoot(): JSONObject {
        return JSONObject(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "messageRenderer": {
                                  "text": { "simpleText": "ignored" }
                                }
                              },
                              {
                                "musicShelfRenderer": {
                                  "title": { "simpleText": "Top results" },
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "thumbnail": {
                                          "musicThumbnailRenderer": {
                                            "thumbnail": {
                                              "thumbnails": [
                                                {
                                                  "url": "https://i.ytimg.com/vi/song-video-id/hqdefault.jpg"
                                                }
                                              ]
                                            }
                                          }
                                        },
                                        "overlay": {
                                          "musicItemThumbnailOverlayRenderer": {
                                            "content": {
                                              "musicPlayButtonRenderer": {
                                                "playNavigationEndpoint": {
                                                  "watchEndpoint": {
                                                    "videoId": "song-video-id",
                                                    "watchEndpointMusicSupportedConfigs": {
                                                      "watchEndpointMusicConfig": {
                                                        "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Song Result",
                                                    "navigationEndpoint": {
                                                      "watchEndpoint": {
                                                        "videoId": "song-video-id"
                                                      }
                                                    }
                                                  }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  { "text": "Song" },
                                                  { "text": " • " },
                                                  { "text": "Artist A" },
                                                  { "text": " • " },
                                                  { "text": "Album A" }
                                                ]
                                              }
                                            }
                                          }
                                        ],
                                        "fixedColumns": [
                                          {
                                            "musicResponsiveListItemFixedColumnRenderer": {
                                              "text": { "simpleText": "3:21" }
                                            }
                                          }
                                        ]
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "thumbnail": {
                                          "musicThumbnailRenderer": {
                                            "thumbnail": {
                                              "thumbnails": [
                                                {
                                                  "url": "https://i.ytimg.com/vi/video-video-id/hqdefault.jpg"
                                                }
                                              ]
                                            }
                                          }
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Video Result",
                                                    "navigationEndpoint": {
                                                      "watchEndpoint": {
                                                        "videoId": "video-video-id"
                                                      }
                                                    }
                                                  }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  { "text": "Video" },
                                                  { "text": " • " },
                                                  { "text": "Artist B" }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "simpleText": "4:05" }
                                            }
                                          }
                                        ]
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "thumbnail": {
                                          "musicThumbnailRenderer": {
                                            "thumbnail": {
                                              "thumbnails": [
                                                {
                                                  "url": "https://i.ytimg.com/vi/artist-result/hqdefault.jpg"
                                                }
                                              ]
                                            }
                                          }
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "simpleText": "Artist Result" }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  { "text": "Artist" },
                                                  { "text": " • " },
                                                  { "text": "1M subscribers" }
                                                ]
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "thumbnail": {
                                          "musicThumbnailRenderer": {
                                            "thumbnail": {
                                              "thumbnails": [
                                                {
                                                  "url": "https://i.ytimg.com/vi/unavailable-song/hqdefault.jpg"
                                                }
                                              ]
                                            }
                                          }
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "simpleText": "Unavailable Song" }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  { "text": "Song" },
                                                  { "text": " • " },
                                                  { "text": "Artist C" },
                                                  { "text": " • " },
                                                  { "text": "Album C" }
                                                ]
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
    }

    private fun createSongSearchResultsRoot(): JSONObject {
        return JSONObject(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "title": { "simpleText": "Songs" },
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "thumbnail": {
                                          "musicThumbnailRenderer": {
                                            "thumbnail": {
                                              "thumbnails": [
                                                {
                                                  "url": "https://i.ytimg.com/vi/song-qt/hqdefault.jpg"
                                                }
                                              ]
                                            }
                                          }
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "晴天",
                                                    "navigationEndpoint": {
                                                      "watchEndpoint": {
                                                        "videoId": "song-qt"
                                                      }
                                                    }
                                                  }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "周杰倫",
                                                    "navigationEndpoint": {
                                                      "browseEndpoint": {
                                                        "browseId": "UC-artist"
                                                      }
                                                    }
                                                  },
                                                  { "text": " • " },
                                                  {
                                                    "text": "葉惠美",
                                                    "navigationEndpoint": {
                                                      "browseEndpoint": {
                                                        "browseId": "MPREb_yehuimei"
                                                      }
                                                    }
                                                  },
                                                  { "text": " • " },
                                                  { "text": "4:30" }
                                                ]
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ],
                                  "continuations": [
                                    {
                                      "nextContinuationData": {
                                        "continuation": "song-shelf-token"
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
    }

    private fun createSongSearchContinuationRoot(): JSONObject {
        return JSONObject(
            """
            {
              "continuationContents": {
                "musicShelfContinuation": {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": {
                          "videoId": "song-anjing"
                        },
                        "thumbnail": {
                          "musicThumbnailRenderer": {
                            "thumbnail": {
                              "thumbnails": [
                                {
                                  "url": "https://i.ytimg.com/vi/song-anjing/hqdefault.jpg"
                                }
                              ]
                            }
                          }
                        },
                        "flexColumns": [
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "simpleText": "安静" }
                            }
                          },
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": {
                                "runs": [
                                  {
                                    "text": "周杰倫",
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UC-artist"
                                      }
                                    }
                                  },
                                  { "text": " • " },
                                  {
                                    "text": "范特西",
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "MPREb_fantasy"
                                      }
                                    }
                                  },
                                  { "text": " • " },
                                  { "text": "5:34" }
                                ]
                              }
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "continuations": [
                    {
                      "nextContinuationData": {
                        "continuation": "song-shelf-next-token"
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
    }

    private fun createWrappedSongSearchResultsRoot(): JSONObject {
        return JSONObject(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "itemSectionRenderer": {
                                  "contents": [
                                    {
                                      "messageRenderer": {
                                        "text": { "simpleText": "ignored" }
                                      }
                                    },
                                    {
                                      "musicShelfRenderer": {
                                        "title": { "simpleText": "Songs" },
                                        "contents": [
                                          {
                                            "musicResponsiveListItemRenderer": {
                                              "thumbnail": {
                                                "musicThumbnailRenderer": {
                                                  "thumbnail": {
                                                    "thumbnails": [
                                                      {
                                                        "url": "https://i.ytimg.com/vi/song-qt/hqdefault.jpg"
                                                      }
                                                    ]
                                                  }
                                                }
                                              },
                                              "flexColumns": [
                                                {
                                                  "musicResponsiveListItemFlexColumnRenderer": {
                                                    "text": {
                                                      "runs": [
                                                        {
                                                          "text": "晴天",
                                                          "navigationEndpoint": {
                                                            "watchEndpoint": {
                                                              "videoId": "song-qt"
                                                            }
                                                          }
                                                        }
                                                      ]
                                                    }
                                                  }
                                                },
                                                {
                                                  "musicResponsiveListItemFlexColumnRenderer": {
                                                    "text": {
                                                      "runs": [
                                                        {
                                                          "text": "周杰倫",
                                                          "navigationEndpoint": {
                                                            "browseEndpoint": {
                                                              "browseId": "UC-artist"
                                                            }
                                                          }
                                                        },
                                                        { "text": " • " },
                                                        {
                                                          "text": "葉惠美",
                                                          "navigationEndpoint": {
                                                            "browseEndpoint": {
                                                              "browseId": "MPREb_yehuimei"
                                                            }
                                                          }
                                                        },
                                                        { "text": " • " },
                                                        { "text": "4:30" }
                                                      ]
                                                    }
                                                  }
                                                }
                                              ]
                                            }
                                          }
                                        ],
                                        "continuations": [
                                          {
                                            "nextContinuationData": {
                                              "continuation": "wrapped-song-shelf-token"
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
    }
}
