package moe.ouom.neriplayer.core.api.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicClientParserTest {

    @Test
    fun parseLibraryPlaylists_shouldExtractTrackCountFromBulletSubtitle() {
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
                                "gridRenderer": {
                                  "items": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "VLP-test"
                                          }
                                        },
                                        "title": {
                                          "runs": [
                                            { "text": "music" }
                                          ]
                                        },
                                        "subtitle": {
                                          "runs": [
                                            { "text": "cwuom" },
                                            { "text": " • " },
                                            { "text": "18 集" }
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
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val playlists = YouTubeMusicParser.parseLibraryPlaylists(root)

        assertEquals(1, playlists.size)
        assertEquals(18, playlists.single().trackCount)
    }

    @Test
    fun parsePlaylistTrackCount_shouldPreferSecondSubtitleOverYearSubtitle() {
        val root = createLikedPlaylistRoot()

        val trackCount = YouTubeMusicParser.parsePlaylistTrackCount(root)

        assertEquals(37, trackCount)
    }

    @Test
    fun parsePlaylistDetail_shouldSupportResponsiveHeaderRenderer() {
        val root = createLikedPlaylistRoot()

        val detail = YouTubeMusicParser.parsePlaylistDetail(
            root = root,
            browseId = "VLLM",
            fallbackTitle = "",
            fallbackSubtitle = "",
            fallbackCoverUrl = ""
        )

        assertEquals("LM", detail.playlistId)
        assertEquals("喜歡的音樂", detail.title)
        assertEquals("自動播放清單 • 2025", detail.subtitle)
        assertEquals(37, detail.trackCount)
    }

    private fun createLikedPlaylistRoot(): JSONObject {
        return JSONObject(
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
                                  "title": {
                                    "runs": [
                                      { "text": "喜歡的音樂" }
                                    ]
                                  },
                                  "subtitle": {
                                    "runs": [
                                      { "text": "自動播放清單" },
                                      { "text": " • " },
                                      { "text": "2025" }
                                    ]
                                  },
                                  "secondSubtitle": {
                                    "runs": [
                                      { "text": "37 首歌" },
                                      { "text": " • " },
                                      { "text": "超過 5 小時" }
                                    ]
                                  },
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
    }
}
