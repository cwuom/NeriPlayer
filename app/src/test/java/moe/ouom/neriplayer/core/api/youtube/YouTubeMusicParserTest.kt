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
