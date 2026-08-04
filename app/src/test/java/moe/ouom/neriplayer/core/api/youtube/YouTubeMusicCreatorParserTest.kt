package moe.ouom.neriplayer.core.api.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicCreatorParserTest {

    @Test
    fun searchFilters_matchSupportedMusicSearchCategories() {
        assertEquals(
            "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
            YouTubeMusicSearchParams.songs()
        )
        assertEquals(
            "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D",
            YouTubeMusicSearchParams.videos()
        )
        assertEquals(
            "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
            YouTubeMusicSearchParams.creators()
        )
    }

    @Test
    fun parseCreatorSearchResults_readsCreatorBrowseEndpoint() {
        val root = JSONObject(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "contents": [{
                                "musicTwoRowItemRenderer": {
                                  "title": { "simpleText": "Demo Creator" },
                                  "subtitle": {
                                    "simpleText": "Artist • 12M monthly listeners"
                                  },
                                  "thumbnailRenderer": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [{
                                          "url": "https://example.com/creator.jpg"
                                        }]
                                      }
                                    }
                                  },
                                  "navigationEndpoint": {
                                    "browseEndpoint": {
                                      "browseId": "UCdemoCreator"
                                    }
                                  }
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()
        )

        val creator = YouTubeMusicParser.parseCreatorSearchResults(root).single()

        assertEquals("UCdemoCreator", creator.browseId)
        assertEquals("UCdemoCreator", creator.channelId)
        assertEquals("Demo Creator", creator.title)
        assertEquals("Artist • 12M monthly listeners", creator.subtitle)
    }

    @Test
    fun parseCreatorDetail_preservesAllBrowseSectionTitlesAndItemKinds() {
        val fallback = YouTubeMusicCreatorSummary(
            browseId = "UCdemoCreator",
            title = "Fallback Creator",
            subtitle = "",
            coverUrl = ""
        )

        val detail = YouTubeMusicParser.parseCreatorDetail(
            root = creatorBrowseRoot(),
            fallback = fallback
        )

        assertEquals("Demo Creator", detail.header.title)
        assertEquals("3.2M subscribers", detail.header.subscriberCountText)
        assertEquals("12M monthly listeners", detail.header.monthlyListenerCountText)
        assertEquals(
            listOf(
                "TOP SONGS",
                "Albums",
                "SINGLES & EPS",
                "Videos",
                "Featured on",
                "Playlists by Demo Creator",
                "Fans might also like"
            ),
            detail.sections.map { it.title }
        )
        assertEquals(
            YouTubeMusicCreatorItemType.Song,
            detail.sections.first { it.title == "TOP SONGS" }.items.single().type
        )
        assertEquals(
            YouTubeMusicCreatorBrowseEndpoint(
                browseId = "UCdemoCreator",
                params = "wAEB8gECAg%3D%3D"
            ),
            detail.sections.first { it.title == "TOP SONGS" }.moreEndpoint
        )
        assertEquals(
            YouTubeMusicCreatorItemType.Album,
            detail.sections.first { it.title == "Albums" }.items.single().type
        )
        assertEquals(
            YouTubeMusicCreatorBrowseEndpoint(
                browseId = "UCdemoCreator",
                params = "wAEB8gECAw%3D%3D"
            ),
            detail.sections.first { it.title == "Albums" }.moreEndpoint
        )
        assertEquals(
            YouTubeMusicCreatorItemType.Album,
            detail.sections.first { it.title == "SINGLES & EPS" }.items.single().type
        )
        assertEquals(
            YouTubeMusicCreatorItemType.Video,
            detail.sections.first { it.title == "Videos" }.items.single().type
        )
        assertEquals(
            YouTubeMusicCreatorItemType.Playlist,
            detail.sections.first { it.title == "Featured on" }.items.single().type
        )
        assertEquals(
            YouTubeMusicCreatorItemType.Playlist,
            detail.sections.first { it.title == "Playlists by Demo Creator" }.items.single().type
        )
        val relatedCreator = detail.sections
            .first { it.title == "Fans might also like" }
            .items
            .single()
        assertEquals(YouTubeMusicCreatorItemType.Creator, relatedCreator.type)
        assertEquals("UCrelatedCreator", relatedCreator.browseId)
        assertTrue(detail.sections.all { it.items.isNotEmpty() })
    }

    @Test
    fun parseCreatorDetail_usesShelfBottomEndpointForTopSongs() {
        val detail = YouTubeMusicParser.parseCreatorDetail(
            root = JSONObject(
                """
                {
                  "contents": {
                    "singleColumnBrowseResultsRenderer": {
                      "tabs": [{
                        "tabRenderer": {
                          "content": {
                            "sectionListRenderer": {
                              "contents": [{
                                "musicShelfRenderer": {
                                  "title": { "simpleText": "TOP SONGS" },
                                  "bottomEndpoint": {
                                    "browseEndpoint": {
                                      "browseId": "UCdemoCreator",
                                      "params": "wAEB8gECAg%3D%3D"
                                    }
                                  },
                                  "contents": [
                                    ${creatorSongRenderer("top-song", "Top Song")}
                                  ]
                                }
                              }]
                            }
                          }
                        }
                      }]
                    }
                  }
                }
                """.trimIndent()
            ),
            fallback = YouTubeMusicCreatorSummary(
                browseId = "UCdemoCreator",
                title = "Demo Creator",
                subtitle = "",
                coverUrl = ""
            )
        )

        assertEquals(
            YouTubeMusicCreatorBrowseEndpoint(
                browseId = "UCdemoCreator",
                params = "wAEB8gECAg%3D%3D"
            ),
            detail.sections.single().moreEndpoint
        )
    }

    @Test
    fun parseCreatorItemsPage_readsAllSongsAndContinuation() {
        val page = YouTubeMusicParser.parseCreatorItemsPage(
            root = JSONObject(
                """
                {
                  "contents": {
                    "singleColumnBrowseResultsRenderer": {
                      "tabs": [{
                        "tabRenderer": {
                          "content": {
                            "sectionListRenderer": {
                              "contents": [{
                                "musicPlaylistShelfRenderer": {
                                  "title": { "simpleText": "All songs" },
                                  "contents": [
                                    ${creatorSongRenderer("all-song-one", "All Song One")},
                                    ${creatorSongRenderer("all-song-two", "All Song Two")}
                                  ],
                                  "continuations": [{
                                    "nextContinuationData": { "continuation": "next-songs" }
                                  }]
                                }
                              }]
                            }
                          }
                        }
                      }]
                    }
                  }
                }
                """.trimIndent()
            ),
            fallbackTitle = "TOP SONGS"
        )

        assertEquals("All songs", page.title)
        assertEquals(listOf("all-song-one", "all-song-two"), page.items.map { it.videoId })
        assertEquals("next-songs", page.continuation)
    }

    @Test
    fun parseCreatorItemsContinuation_readsAppendedSongsAndNextToken() {
        val page = YouTubeMusicParser.parseCreatorItemsContinuation(
            root = JSONObject(
                """
                {
                  "onResponseReceivedActions": [{
                    "appendContinuationItemsAction": {
                      "continuationItems": [
                        ${creatorSongRenderer("continued-song", "Continued Song")},
                        {
                          "continuationItemRenderer": {
                            "continuationEndpoint": {
                              "continuationCommand": { "token": "last-songs" }
                            }
                          }
                        }
                      ]
                    }
                  }]
                }
                """.trimIndent()
            )
        )

        assertEquals(listOf("continued-song"), page.items.map { it.videoId })
        assertEquals("last-songs", page.continuation)
    }

    private fun creatorSongRenderer(videoId: String, title: String): String {
        return """
            {
              "musicResponsiveListItemRenderer": {
                "playlistItemData": { "videoId": "$videoId" },
                "navigationEndpoint": { "watchEndpoint": { "videoId": "$videoId" } },
                "flexColumns": [
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "simpleText": "$title" }
                    }
                  },
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "simpleText": "Song • Demo Creator • 3:20" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()
    }

    private fun creatorBrowseRoot(): JSONObject {
        return JSONObject(
            """
            {
              "header": {
                "musicImmersiveHeaderRenderer": {
                  "title": { "simpleText": "Demo Creator" },
                  "subtitle": { "simpleText": "Artist" },
                  "description": { "simpleText": "A creator profile." },
                  "shortSubscriberCountText": {
                    "simpleText": "3.2M subscribers"
                  },
                  "monthlyListenerCount": {
                    "simpleText": "12M monthly listeners"
                  },
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [{
                          "url": "https://example.com/header.jpg"
                        }]
                      }
                    }
                  }
                }
              },
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [
                            {
                              "musicShelfRenderer": {
                                "title": {
                                  "runs": [{
                                    "text": "TOP SONGS",
                                    "navigationEndpoint": { "browseEndpoint": {
                                      "browseId": "UCdemoCreator",
                                      "params": "wAEB8gECAg%3D%3D"
                                    }}
                                  }]
                                },
                                "contents": [{
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "top-song" },
                                    "navigationEndpoint": {
                                      "watchEndpoint": {
                                        "videoId": "top-song",
                                        "watchEndpointMusicSupportedConfigs": {
                                          "watchEndpointMusicConfig": {
                                            "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                          }
                                        }
                                      }
                                    },
                                    "thumbnail": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": {
                                          "thumbnails": [{
                                            "url": "https://example.com/top.jpg"
                                          }]
                                        }
                                      }
                                    },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "simpleText": "Top Song" }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "simpleText": "Song • Demo Creator • 3:20"
                                          }
                                        }
                                      }
                                    ]
                                  }
                                }]
                              }
                            },
                            {
                              "musicCarouselShelfRenderer": {
                                "header": {
                                  "musicCarouselShelfBasicHeaderRenderer": {
                                    "title": { "simpleText": "Albums" },
                                    "moreContentButton": {
                                      "buttonRenderer": {
                                        "navigationEndpoint": { "browseEndpoint": {
                                          "browseId": "UCdemoCreator",
                                          "params": "wAEB8gECAw%3D%3D"
                                        }}
                                      }
                                    }
                                  }
                                },
                                "contents": [
                                  { "musicTwoRowItemRenderer": {
                                    "title": { "simpleText": "Album One" },
                                    "subtitle": { "simpleText": "2025" },
                                    "navigationEndpoint": { "browseEndpoint": {
                                      "browseId": "MPREalbumOne",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_ALBUM"
                                        }
                                      }
                                    }}
                                  }}
                                ]
                              }
                            },
                            {
                              "musicCarouselShelfRenderer": {
                                "header": {
                                  "musicCarouselShelfBasicHeaderRenderer": {
                                    "title": { "simpleText": "SINGLES & EPS" }
                                  }
                                },
                                "contents": [
                                  { "musicTwoRowItemRenderer": {
                                    "title": { "simpleText": "Single One" },
                                    "subtitle": { "simpleText": "2026" },
                                    "navigationEndpoint": { "browseEndpoint": {
                                      "browseId": "MPREsingleOne",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_ALBUM"
                                        }
                                      }
                                    }}
                                  }}
                                ]
                              }
                            },
                            {
                              "musicCarouselShelfRenderer": {
                                "header": {
                                  "musicCarouselShelfBasicHeaderRenderer": {
                                    "title": { "simpleText": "Videos" }
                                  }
                                },
                                "contents": [
                                  { "musicTwoRowItemRenderer": {
                                    "title": { "simpleText": "Video One" },
                                    "subtitle": { "simpleText": "Demo Creator • 4:20" },
                                    "navigationEndpoint": { "watchEndpoint": {
                                      "videoId": "video-one",
                                      "watchEndpointMusicSupportedConfigs": {
                                        "watchEndpointMusicConfig": {
                                          "musicVideoType": "MUSIC_VIDEO_TYPE_OMV"
                                        }
                                      }
                                    }}
                                  }}
                                ]
                              }
                            },
                            {
                              "musicCarouselShelfRenderer": {
                                "header": {
                                  "musicCarouselShelfBasicHeaderRenderer": {
                                    "title": { "simpleText": "Featured on" }
                                  }
                                },
                                "contents": [
                                  { "musicTwoRowItemRenderer": {
                                    "title": { "simpleText": "Featured Playlist" },
                                    "navigationEndpoint": { "browseEndpoint": {
                                      "browseId": "VLfeaturedOne",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                        }
                                      }
                                    }}
                                  }}
                                ]
                              }
                            },
                            {
                              "musicCarouselShelfRenderer": {
                                "header": {
                                  "musicCarouselShelfBasicHeaderRenderer": {
                                    "title": {
                                      "simpleText": "Playlists by Demo Creator"
                                    }
                                  }
                                },
                                "contents": [
                                  { "musicTwoRowItemRenderer": {
                                    "title": { "simpleText": "Creator Playlist" },
                                    "navigationEndpoint": { "browseEndpoint": {
                                      "browseId": "VLcreatorOne",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                        }
                                      }
                                    }}
                                  }}
                                ]
                              }
                            },
                            {
                              "musicCarouselShelfRenderer": {
                                "header": {
                                  "musicCarouselShelfBasicHeaderRenderer": {
                                    "title": { "simpleText": "Fans might also like" }
                                  }
                                },
                                "contents": [
                                  { "musicTwoRowItemRenderer": {
                                    "title": { "simpleText": "Related Creator" },
                                    "navigationEndpoint": { "browseEndpoint": {
                                      "browseId": "UCrelatedCreator",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                        }
                                      }
                                    }}
                                  }}
                                ]
                              }
                            }
                          ]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()
        )
    }
}
