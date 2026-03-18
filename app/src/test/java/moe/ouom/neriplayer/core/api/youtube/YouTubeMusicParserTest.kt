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
}
