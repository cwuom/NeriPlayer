package moe.ouom.neriplayer.core.api.youtube

import java.util.Locale
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun parseLibraryPlaylists_shouldReadNestedPlaylistCards() {
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
                                "itemSectionRenderer": {
                                  "contents": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "VLRDCLAK5uy_user_mix",
                                            "browseEndpointContextSupportedConfigs": {
                                              "browseEndpointContextMusicConfig": {
                                                "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                              }
                                            }
                                          }
                                        },
                                        "title": {
                                          "simpleText": "用户歌单"
                                        },
                                        "subtitle": {
                                          "simpleText": "23 首歌"
                                        }
                                      }
                                    },
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "UC-artist",
                                            "browseEndpointContextSupportedConfigs": {
                                              "browseEndpointContextMusicConfig": {
                                                "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                              }
                                            }
                                          }
                                        },
                                        "title": {
                                          "simpleText": "不是歌单的歌手"
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
        assertEquals("用户歌单", playlists.single().title)
        assertEquals("RDCLAK5uy_user_mix", playlists.single().playlistId)
        assertEquals(23, playlists.single().trackCount)
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

    @Test
    fun parseBootstrapConfig_extractsLoggedInUserSessionFromDataSyncId() {
        val bootstrap = YouTubeMusicParser.parseBootstrapConfig(
            html = """
                "INNERTUBE_API_KEY":"api-key"
                "INNERTUBE_CLIENT_VERSION":"1.20260325.01.00"
                "VISITOR_DATA":"visitor-data"
                "SESSION_INDEX":"3"
                "DATASYNC_ID":"delegated-session||user-session"
                "LOGGED_IN":true
            """.trimIndent(),
            cookieHeader = "SAPISID=sap-value",
            userAgent = "UnitTestAgent/9.0"
        )

        assertEquals("3", bootstrap.sessionIndex)
        assertTrue(bootstrap.loggedIn)
        assertEquals("user-session", bootstrap.userSessionId)
    }

    @Test
    fun parseBootstrapConfig_supportsYtcfgJsonWithEscapedQuotes() {
        val bootstrap = YouTubeMusicParser.parseBootstrapConfig(
            html = """
                <script>
                ytcfg.set({
                  \x22INNERTUBE_API_KEY\x22 : \x22api-key\x22,
                  \x22INNERTUBE_CONTEXT_CLIENT_VERSION\x22 : \x221.20260325.02.00\x22,
                  \x22VISITOR_DATA\x22 : \x22visitor-data\x22,
                  \x22DATASYNC_ID\x22 : \x22delegated-session||user-session\x22,
                  \x22LOGGED_IN\x22 : false
                });
                </script>
            """.trimIndent(),
            cookieHeader = "",
            userAgent = "UnitTestAgent/10.0"
        )

        assertEquals("api-key", bootstrap.apiKey)
        assertEquals("1.20260325.02.00", bootstrap.webRemixClientVersion)
        assertEquals("visitor-data", bootstrap.visitorData)
        assertEquals("0", bootstrap.sessionIndex)
        assertFalse(bootstrap.loggedIn)
        assertEquals("user-session", bootstrap.userSessionId)
    }

    @Test
    fun hasEffectiveLogin_acceptsSavedLoginCookiesWhenBootstrapIsFalse() {
        val bootstrap = YouTubeMusicParser.parseBootstrapConfig(
            html = """
                "INNERTUBE_API_KEY":"api-key"
                "INNERTUBE_CLIENT_VERSION":"1.20260325.03.00"
                "VISITOR_DATA":"visitor-data"
                "LOGGED_IN":false
            """.trimIndent(),
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            userAgent = "UnitTestAgent/11.0"
        )
        val auth = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value"
        )

        assertTrue(bootstrap.hasEffectiveLogin(auth))
    }

    @Test
    fun hasEffectiveLogin_acceptsAuthorizationWhenBootstrapIsFalse() {
        val bootstrap = YouTubeMusicParser.parseBootstrapConfig(
            html = """
                "INNERTUBE_API_KEY":"api-key"
                "INNERTUBE_CLIENT_VERSION":"1.20260325.05.00"
                "VISITOR_DATA":"visitor-data"
                "LOGGED_IN":false
            """.trimIndent(),
            cookieHeader = "SOCS=CAI; VISITOR_INFO1_LIVE=visitor",
            userAgent = "UnitTestAgent/13.0"
        )
        val auth = YouTubeAuthBundle(
            authorization = "SAPISIDHASH 123_hash"
        )

        assertTrue(bootstrap.hasEffectiveLogin(auth))
    }

    @Test
    fun hasEffectiveLogin_rejectsVisitorOnlyCookiesWhenBootstrapIsFalse() {
        val bootstrap = YouTubeMusicParser.parseBootstrapConfig(
            html = """
                "INNERTUBE_API_KEY":"api-key"
                "INNERTUBE_CLIENT_VERSION":"1.20260325.04.00"
                "VISITOR_DATA":"visitor-data"
                "LOGGED_IN":false
            """.trimIndent(),
            cookieHeader = "SOCS=CAI; VISITOR_INFO1_LIVE=visitor",
            userAgent = "UnitTestAgent/12.0"
        )
        val auth = YouTubeAuthBundle(
            cookieHeader = "SOCS=CAI; VISITOR_INFO1_LIVE=visitor"
        )

        assertFalse(bootstrap.hasEffectiveLogin(auth))
    }

    @Test
    fun requestCandidates_appendsUsFallbackForNonUsLocale() {
        val candidates = YouTubeMusicLocaleResolver.requestCandidates(
            preferredLocale = YouTubeMusicLocaleResolver.preferred(Locale.forLanguageTag("zh-CN"))
        )

        assertEquals(
            listOf(
                YouTubeMusicRequestLocale(hl = "zh-CN", gl = "JP")
            ),
            candidates
        )
    }

    @Test
    fun shouldRetryWithSafeFallback_returnsTrueForInitialBrowseWithoutContents() {
        val payload = JSONObject().put("browseId", "VLLM")
        val root = JSONObject(
            """
            {
              "responseContext": {},
              "microformat": {}
            }
            """.trimIndent()
        )

        assertTrue(YouTubeMusicLocaleResolver.shouldRetryWithSafeFallback(payload, root))
    }

    @Test
    fun shouldRetryWithSafeFallback_returnsFalseForContinuationPayload() {
        val payload = JSONObject().put("continuation", "token-123")

        assertFalse(
            YouTubeMusicLocaleResolver.shouldRetryWithSafeFallback(
                payload = payload,
                root = JSONObject()
            )
        )
    }

    @Test
    fun shouldRetryWithSafeFallback_returnsFalseWhenBrowseContentsExist() {
        val payload = JSONObject().put("browseId", "VLLM")
        val root = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {}
              }
            }
            """.trimIndent()
        )

        assertFalse(YouTubeMusicLocaleResolver.shouldRetryWithSafeFallback(payload, root))
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
