package moe.ouom.neriplayer.platform.bilibili.api.parser

import moe.ouom.neriplayer.data.model.bilibili.collection.CollectionArchiveItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliUploaderParserFallbackTest {
    @Test
    fun `profile keeps a positive mid and replaces non-positive mids with the fallback`() {
        assertEquals(77L, parseBiliUploaderProfile(JSONObject("""{"mid":77}"""), fallbackMid = 123L).mid)
        assertEquals(123L, parseBiliUploaderProfile(JSONObject("""{"mid":0}"""), fallbackMid = 123L).mid)
        assertEquals(123L, parseBiliUploaderProfile(JSONObject("""{"mid":-5}"""), fallbackMid = 123L).mid)
    }

    @Test
    fun `content page without item lists has no entries and no further page`() {
        val page = parseBiliUploaderContentPage(
            data = JSONObject("{}"),
            requestedPage = 3,
            requestedPageSize = 20,
            fallbackMid = 9L
        )

        assertEquals(3, page.page)
        assertEquals(20, page.pageSize)
        assertTrue(page.collections.isEmpty())
        assertTrue(page.series.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `series archives drop invalid entries and read optional counters`() {
        val page = parseBiliSeriesArchivePage(
            data = JSONObject(
                """
                {
                  "page": {"num": 1, "size": 2, "total": 9},
                  "archives": [
                    "not-an-object",
                    {"aid": 0, "bvid": "BV0", "title": "zero aid"},
                    {"aid": 1, "bvid": " ", "title": "blank bvid"},
                    {"aid": 2, "bvid": "BV2", "title": ""},
                    {"aid": 3, "bvid": "BV3", "title": "string counters", "pic": "http://i0.hdslb.com/3.jpg",
                     "pubdate": "1700000000", "stat": {"view": "42"}},
                    {"aid": 4, "bvid": "BV4", "title": "null counters", "pubdate": null, "stat": {"view": null}},
                    {"aid": 5, "bvid": "BV5", "title": "bad counters", "pubdate": "soon", "stat": {}},
                    {"aid": 6, "bvid": "BV6", "title": "numeric counters", "duration": 61, "pubdate": 1700000001,
                     "stat": {"view": 7}}
                  ]
                }
                """.trimIndent()
            ),
            requestedPage = 1,
            requestedPageSize = 30
        )

        assertEquals(
            listOf(
                CollectionArchiveItem(3L, "BV3", "string counters", "https://i0.hdslb.com/3.jpg", 0, 1_700_000_000L, 42L),
                CollectionArchiveItem(4L, "BV4", "null counters", "", 0, null, null),
                CollectionArchiveItem(5L, "BV5", "bad counters", "", 0, null, null),
                CollectionArchiveItem(6L, "BV6", "numeric counters", "", 61, 1_700_000_001L, 7L)
            ),
            page.items
        )
        assertEquals(9, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `series archives without data fall back to the request and item count`() {
        val page = parseBiliSeriesArchivePage(JSONObject("{}"), requestedPage = 2, requestedPageSize = 30)

        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
        assertFalse(page.hasMore)
    }
}
