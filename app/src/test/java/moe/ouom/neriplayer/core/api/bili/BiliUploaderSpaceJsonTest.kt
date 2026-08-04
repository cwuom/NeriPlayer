package moe.ouom.neriplayer.core.api.bili

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.api.bili/BiliUploaderSpaceJsonTest
 * Created: 2026/8/3
 */

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliUploaderSpaceJsonTest {
    @Test
    fun parseProfile_normalizesProtocolRelativeImagesAndKeepsFallbackMid() {
        val profile = parseBiliUploaderProfile(
            data = JSONObject(
                """
                {
                  "name": "测试 UP 主",
                  "face": "//i0.hdslb.com/bfs/face.jpg",
                  "sign": "简介",
                  "top_photo": "//i0.hdslb.com/bfs/banner.jpg"
                }
                """.trimIndent()
            ),
            fallbackMid = 123L
        )

        assertEquals(123L, profile.mid)
        assertEquals("https://i0.hdslb.com/bfs/face.jpg", profile.faceUrl)
        assertEquals("https://i0.hdslb.com/bfs/banner.jpg", profile.topPhotoUrl)
        assertEquals("测试 UP 主", profile.name)
    }

    @Test
    fun parseVideoPage_readsPagingDurationAndCoauthorMid() {
        val page = parseBiliUploaderVideoPage(
            data = JSONObject(
                """
                {
                  "page": {"pn": 2, "ps": 30, "count": 61},
                  "list": {
                    "vlist": [
                      {
                        "aid": 1,
                        "bvid": "BV1test",
                        "title": "<em class=\"keyword\">测试</em>投稿",
                        "pic": "//i0.hdslb.com/bfs/archive.jpg",
                        "length": "1:02:03",
                        "mid": 456,
                        "author": "共同创作 UP 主",
                        "play": "321",
                        "created": 1700000000
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
            requestedPage = 1,
            requestedPageSize = 30,
            fallbackMid = 123L
        )

        assertEquals(2, page.page)
        assertEquals(61, page.total)
        assertTrue(page.hasMore)
        assertEquals(1, page.items.size)
        assertEquals("测试投稿", page.items.single().title)
        assertEquals(3_723, page.items.single().durationSec)
        assertEquals(456L, page.items.single().uploaderMid)
        assertEquals(321L, page.items.single().play)
        assertEquals("https://i0.hdslb.com/bfs/archive.jpg", page.items.single().coverUrl)
    }

    @Test
    fun parseContents_keepsCollectionAndSeriesSeparate() {
        val page = parseBiliUploaderContentPage(
            data = JSONObject(
                """
                {
                  "items_lists": {
                    "page": {"page_num": 1, "page_size": 20, "total": 21},
                    "seasons_list": [
                      {
                        "meta": {
                          "season_id": 10,
                          "mid": 123,
                          "name": "合集标题",
                          "cover": "http://i0.hdslb.com/bfs/collection.jpg",
                          "description": "合集简介",
                          "total": 3
                        }
                      }
                    ],
                    "series_list": [
                      {
                        "meta": {
                          "series_id": 20,
                          "mid": 123,
                          "name": "系列标题",
                          "cover": "//i0.hdslb.com/bfs/series.jpg",
                          "description": "系列简介",
                          "total": 4
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
            requestedPage = 1,
            requestedPageSize = 20,
            fallbackMid = 999L
        )

        assertTrue(page.hasMore)
        assertEquals(BiliClient.UploaderContentKind.COLLECTION, page.collections.single().kind)
        assertEquals(10L, page.collections.single().id)
        assertEquals("https://i0.hdslb.com/bfs/collection.jpg", page.collections.single().coverUrl)
        assertEquals(BiliClient.UploaderContentKind.SERIES, page.series.single().kind)
        assertEquals(20L, page.series.single().id)
        assertEquals(4, page.series.single().total)
    }

    @Test
    fun parseSeriesArchives_readsItemsAndPaging() {
        val page = parseBiliSeriesArchivePage(
            data = JSONObject(
                """
                {
                  "page": {"num": 1, "size": 30, "total": 31},
                  "archives": [
                    {
                      "aid": 1,
                      "bvid": "BV1series",
                      "title": "系列视频",
                      "pic": "//i0.hdslb.com/bfs/series-video.jpg",
                      "duration": 99,
                      "pubdate": 1700000000,
                      "stat": {"view": 42}
                    }
                  ]
                }
                """.trimIndent()
            ),
            requestedPage = 1,
            requestedPageSize = 30
        )

        assertTrue(page.hasMore)
        assertEquals(31, page.total)
        assertEquals(42L, page.items.single().play)
        assertEquals(99, page.items.single().durationSec)
        assertFalse(page.items.single().coverUrl.startsWith("//"))
    }
}
