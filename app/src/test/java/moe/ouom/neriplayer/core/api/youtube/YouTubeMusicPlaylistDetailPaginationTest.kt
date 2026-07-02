package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicPlaylistDetailPaginationTest {

    @Test
    fun getPlaylistDetail_collectsTracksAcrossMultipleContinuationPages() = runBlocking {
        val responses = listOf(
            initialPageRoot(videoId = "video-1", title = "Song 1", continuation = "token-a"),
            continuationPageRoot(videoId = "video-2", title = "Song 2", continuation = "token-b"),
            continuationPageRoot(videoId = "video-3", title = "Song 3", continuation = null)
        )
        val requests = mutableListOf<String>()
        var nextResponse = 0

        val detail = collectYouTubeMusicPlaylistDetail(
            browseId = "VLTEST",
            fallbackTitle = "Fallback",
            fallbackSubtitle = "",
            fallbackCoverUrl = "",
            pageLimit = 10
        ) { payload ->
            requests += if (payload.has("browseId")) {
                "browse:${payload.optString("browseId")}"
            } else {
                "continuation:${payload.optString("continuation")}"
            }
            responses[nextResponse++]
        }

        assertEquals(listOf("video-1", "video-2", "video-3"), detail.tracks.map { it.videoId })
        assertEquals(listOf("browse:VLTEST", "continuation:token-a", "continuation:token-b"), requests)
        assertEquals(3, nextResponse)
    }

    @Test
    fun getPlaylistDetail_doesNotReportTrackCountBelowLoadedTracks() = runBlocking {
        val responses = listOf(
            initialPageRoot(
                videoId = "video-1",
                title = "Song 1",
                continuation = "token-a",
                headerTrackCount = 1
            ),
            continuationPageRoot(videoId = "video-2", title = "Song 2", continuation = "token-b"),
            continuationPageRoot(videoId = "video-3", title = "Song 3", continuation = null)
        )
        var nextResponse = 0

        val detail = collectYouTubeMusicPlaylistDetail(
            browseId = "VLTEST",
            fallbackTitle = "Fallback",
            fallbackSubtitle = "",
            fallbackCoverUrl = "",
            pageLimit = 10
        ) { responses[nextResponse++] }

        assertEquals(3, detail.tracks.size)
        assertEquals(3, detail.trackCount)
    }

    private fun initialPageRoot(
        videoId: String,
        title: String,
        continuation: String?,
        headerTrackCount: Int? = null
    ): JSONObject {
        val secondSubtitle = headerTrackCount?.let { count ->
            """
            ,
            "secondSubtitle": {
              "runs": [ { "text": "$count 首歌" } ]
            }
            """.trimIndent()
        }.orEmpty()
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
                                  "title": { "simpleText": "Virtual Playlist" }
                                  $secondSubtitle
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
                            "playlistId": "TEST",
                            "contents": [
                              ${playlistItemJson(videoId, title)}
                              ${continuationItemJsonSuffix(continuation)}
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
    }

    private fun continuationPageRoot(videoId: String, title: String, continuation: String?): JSONObject {
        return JSONObject(
            """
            {
              "onResponseReceivedActions": [
                {
                  "appendContinuationItemsAction": {
                    "continuationItems": [
                      ${playlistItemJson(videoId, title)}
                      ${continuationItemJsonSuffix(continuation)}
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )
    }

    private fun playlistItemJson(videoId: String, title: String): String {
        return """
        {
          "musicResponsiveListItemRenderer": {
            "playlistItemData": { "videoId": "$videoId" },
            "flexColumns": [
              {
                "musicResponsiveListItemFlexColumnRenderer": {
                  "text": { "simpleText": "$title" }
                }
              },
              {
                "musicResponsiveListItemFlexColumnRenderer": {
                  "text": { "simpleText": "Artist" }
                }
              }
            ]
          }
        }
        """.trimIndent()
    }

    private fun continuationItemJsonSuffix(continuation: String?): String {
        if (continuation.isNullOrBlank()) {
            return ""
        }
        return """
        ,
        {
          "continuationItemRenderer": {
            "continuationEndpoint": {
              "continuationCommand": { "token": "$continuation" }
            }
          }
        }
        """.trimIndent()
    }
}
