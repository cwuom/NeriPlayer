package moe.ouom.neriplayer.core.api.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicLyricsAndCoverTest {

    // ─── upgradeYouTubeThumbnailUrl ───

    @Test
    fun upgradeYouTubeThumbnailUrl_replacesSizeParamsWithHighRes() {
        val url = "https://lh3.googleusercontent.com/abc123=w226-h226-l90-rj"
        val upgraded = upgradeYouTubeThumbnailUrl(url)
        assertEquals("https://lh3.googleusercontent.com/abc123=w1200-h1200", upgraded)
    }

    @Test
    fun upgradeYouTubeThumbnailUrl_replacesSmallSizeParams() {
        val url = "https://lh3.googleusercontent.com/abc123=w60-h60-l90-rj"
        val upgraded = upgradeYouTubeThumbnailUrl(url)
        assertEquals("https://lh3.googleusercontent.com/abc123=w1200-h1200", upgraded)
    }

    @Test
    fun upgradeYouTubeThumbnailUrl_appendsSizeForGoogleUrlWithoutParams() {
        val url = "https://lh3.googleusercontent.com/abc123"
        val upgraded = upgradeYouTubeThumbnailUrl(url)
        assertEquals("https://lh3.googleusercontent.com/abc123=w1200-h1200", upgraded)
    }

    @Test
    fun upgradeYouTubeThumbnailUrl_preservesNonGoogleUrls() {
        val url = "https://i.ytimg.com/vi/abc123/maxresdefault.jpg"
        val upgraded = upgradeYouTubeThumbnailUrl(url)
        assertEquals(url, upgraded)
    }

    @Test
    fun upgradeYouTubeThumbnailUrl_handlesBlankUrl() {
        assertEquals("", upgradeYouTubeThumbnailUrl(""))
        assertEquals("  ", upgradeYouTubeThumbnailUrl("  "))
    }

    @Test
    fun upgradeYouTubeThumbnailUrl_handlesYt3GgphtUrl() {
        val url = "https://yt3.ggpht.com/abc123=w120-h120"
        val upgraded = upgradeYouTubeThumbnailUrl(url)
        assertEquals("https://yt3.ggpht.com/abc123=w1200-h1200", upgraded)
    }

    // ─── parseLyricsBrowseId ───

    @Test
    fun parseLyricsBrowseId_extractsFromNextResponse() {
        val root = JSONObject(
            """
            {
              "contents": {
                "singleColumnMusicWatchNextResultsRenderer": {
                  "tabbedRenderer": {
                    "watchNextTabbedResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "title": "Up Next",
                            "endpoint": {
                              "browseEndpoint": {
                                "browseId": "MPREb_something"
                              }
                            }
                          }
                        },
                        {
                          "tabRenderer": {
                            "title": "Lyrics",
                            "endpoint": {
                              "browseEndpoint": {
                                "browseId": "MPLYt_abc123def"
                              }
                            }
                          }
                        },
                        {
                          "tabRenderer": {
                            "title": "Related",
                            "endpoint": {
                              "browseEndpoint": {
                                "browseId": "MPREb_other"
                              }
                            }
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

        val browseId = YouTubeMusicParser.parseLyricsBrowseId(root)
        assertEquals("MPLYt_abc123def", browseId)
    }

    @Test
    fun parseLyricsBrowseId_returnsNullWhenNoLyricsTab() {
        val root = JSONObject(
            """
            {
              "contents": {
                "singleColumnMusicWatchNextResultsRenderer": {
                  "tabbedRenderer": {
                    "watchNextTabbedResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "title": "Up Next",
                            "endpoint": {
                              "browseEndpoint": {
                                "browseId": "MPREb_something"
                              }
                            }
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

        assertNull(YouTubeMusicParser.parseLyricsBrowseId(root))
    }

    @Test
    fun parseLyricsBrowseId_returnsNullForEmptyResponse() {
        assertNull(YouTubeMusicParser.parseLyricsBrowseId(JSONObject()))
    }

    // ─── parseLyrics ───

    @Test
    fun parseLyrics_extractsDescriptionShelfLyrics() {
        val root = JSONObject(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicDescriptionShelfRenderer": {
                        "description": {
                          "runs": [
                            { "text": "Line one\nLine two\nLine three" }
                          ]
                        },
                        "footer": {
                          "runs": [
                            { "text": "Source: Musixmatch" }
                          ]
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val lyrics = YouTubeMusicParser.parseLyrics(root)
        assertNotNull(lyrics)
        assertTrue(lyrics!!.lyrics.contains("Line one"))
        assertTrue(lyrics.lyrics.contains("Line two"))
        assertTrue(lyrics.lyrics.contains("Line three"))
        assertEquals("Source: Musixmatch", lyrics.source)
    }

    @Test
    fun parseLyrics_returnsNullForEmptyResponse() {
        assertNull(YouTubeMusicParser.parseLyrics(JSONObject()))
    }

    @Test
    fun parseLyrics_returnsNullWhenNoSections() {
        val root = JSONObject(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": []
                }
              }
            }
            """.trimIndent()
        )

        assertNull(YouTubeMusicParser.parseLyrics(root))
    }

    @Test
    fun parseLyrics_returnsNullWhenDescriptionBlank() {
        val root = JSONObject(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicDescriptionShelfRenderer": {
                        "description": {
                          "runs": [
                            { "text": "   " }
                          ]
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertNull(YouTubeMusicParser.parseLyrics(root))
    }
}
