package moe.ouom.neriplayer.platform.bilibili.api.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.data.model.bilibili.collection.CollectionArchiveItem
import moe.ouom.neriplayer.data.model.bilibili.collection.CollectionMeta
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import moe.ouom.neriplayer.data.model.bilibili.collection.FavResourceItem
import moe.ouom.neriplayer.data.model.bilibili.collection.FavResourcePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliClientFavoriteFolderTest {
    @Test
    fun `created folders from a complete list-all response skip paged requests`() = biliClientTest(
        route = { request ->
            json("""{"code":0,"data":{"count":2,"list":[$FULL_FOLDER,{"id":1002,"title":"音乐"}]}}""")
                .takeIf { request.url.encodedPath == CREATED_LIST_ALL_PATH }
        }
    ) { http, client ->
        val folders = client.getUserCreatedFavFolders(42L)

        assertEquals(listOf(FULL_FOLDER_MODEL, FavFolder(1002L, 0L, 0L, "音乐", "", "", 0, null, null, null)), folders)
        val url = http.requestsTo(CREATED_LIST_ALL_PATH).single().url
        assertEquals("42", url.queryParameter("up_mid"))
        assertEquals("333.1387", url.queryParameter("web_location"))
        assertTrue(http.requestsTo(CREATED_LIST_PATH).isEmpty())
    }

    @Test
    fun `truncated list-all is merged with paged folders dropping invalid and duplicate entries`() = biliClientTest(
        route = { request ->
            when (request.url.encodedPath) {
                CREATED_LIST_ALL_PATH -> json("""{"code":0,"data":{"count":3,"list":[$FULL_FOLDER,{"id":1002,"title":"音乐"}]}}""")
                CREATED_LIST_PATH -> json(
                    """
                    {"code":0,"data":{"count":3,"list":[
                      $FULL_FOLDER, {"id":0,"title":"zero"}, {"id":1004,"title":"  "}, {"id":1003,"name":"按名称"}
                    ]}}
                    """.trimIndent()
                )
                else -> null
            }
        }
    ) { http, client ->
        val folders = client.getUserCreatedFavFolders(42L)

        assertEquals(listOf(1001L, 1002L, 1003L), folders.map { it.mediaId })
        assertEquals("按名称", folders.last().title)
        val paged = http.requestsTo(CREATED_LIST_PATH).single().url
        assertEquals("1", paged.queryParameter("pn"))
        assertEquals("20", paged.queryParameter("ps"))
    }

    @Test
    fun `collected folders page until the reported count and map collections`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != COLLECTED_LIST_PATH) {
                null
            } else when (request.url.queryParameter("pn")) {
                "1" -> json("""{"code":0,"data":{"count":3,"list":[$COLLECTED_FOLDER,$COLLECTED_SEASON]}}""")
                "2" -> json("""{"code":0,"data":{"count":3,"list":[{"id":2002,"title":"another","mid":9}]}}""")
                else -> null
            }
        }
    ) { http, client ->
        val folders = client.getUserCollectedFavFolders(42L)

        assertEquals(
            listOf(
                FavFolder(2001L, 0L, 7L, "他人收藏夹", "", "", 0, null, null, null, upperName = "别人", itemType = 11),
                FavFolder(3001L, 3001L, 8L, "视频合集", "", "合集简介", 8, null, null, null, upperName = "合集作者", itemType = 21),
                FavFolder(2002L, 0L, 9L, "another", "", "", 0, null, null, null)
            ),
            folders
        )
        val pages = http.requestsTo(COLLECTED_LIST_PATH).map { it.url }
        assertEquals(listOf("1", "2"), pages.map { it.queryParameter("pn") })
        assertEquals(listOf("web", "web"), pages.map { it.queryParameter("platform") })
    }

    @Test
    fun `collected folders stop on missing count empty page or missing data`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != COLLECTED_LIST_PATH) {
                null
            } else when (request.url.queryParameter("up_mid") to request.url.queryParameter("pn")) {
                "1" to "1" -> json("""{"code":0,"data":{"count":0,"list":[$COLLECTED_FOLDER]}}""")
                "2" to "1" -> json("""{"code":0,"data":{"count":5,"list":[$COLLECTED_FOLDER]}}""")
                "2" to "2" -> json("""{"code":0,"data":{"count":5,"list":[]}}""")
                "3" to "1" -> json("""{"code":-101}""")
                else -> null
            }
        }
    ) { http, client ->
        assertEquals(listOf(2001L), client.getUserCollectedFavFolders(1L).map { it.mediaId })
        assertEquals(listOf(2001L), client.getUserCollectedFavFolders(2L).map { it.mediaId })
        assertEquals(emptyList<FavFolder>(), client.getUserCollectedFavFolders(3L))
        assertEquals(
            listOf("1", "2", "2", "3"),
            http.requestsTo(COLLECTED_LIST_PATH).map { it.url.queryParameter("up_mid") }
        )
    }

    @Test
    fun `folder info maps counters or falls back to defaults`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != FOLDER_INFO_PATH) {
                null
            } else when (request.url.queryParameter("media_id")) {
                "1001" -> json("""{"code":0,"data":$FULL_FOLDER}""")
                "1002" -> json("""{"code":-403}""")
                else -> null
            }
        }
    ) { _, client ->
        assertEquals(FULL_FOLDER_MODEL, client.getFavFolderInfo(1001L))
        assertEquals(FavFolder(0L, 0L, 0L, "", "", "", 0, null, null, null), client.getFavFolderInfo(1002L))
    }

    @Test
    fun `folder contents forward filters and parse media entries`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != RESOURCE_LIST_PATH) {
                null
            } else {
                json(
                    """
                    {"code":0,"data":{"info":$FULL_FOLDER,"has_more":true,"medias":[
                      {"type":2,"id":11,"bvid":"BV11","title":"one","cover":"//i0.hdslb.com/11.jpg","intro":"i",
                       "duration":61,"upper":{"mid":5,"name":"U5"},"cnt_info":{"play":100,"danmaku":3},"fav_time":1700000000},
                      {"type":2,"id":12,"bvid":"","bv_id":"BV12","title":"two"},
                      {"type":12,"id":13,"title":"audio"},
                      9
                    ]}}
                    """.trimIndent()
                )
            }
        }
    ) { http, client ->
        val page = client.getFavFolderContents(
            mediaId = 1001L, page = 2, pageSize = 10, order = "view", keyword = "lofi", tid = 3, scopeType = 1
        )
        client.getFavFolderContents(mediaId = 1001L)

        val (filtered, plain) = http.requestsTo(RESOURCE_LIST_PATH).map { it.url }
        assertEquals(
            mapOf(
                "media_id" to "1001", "pn" to "2", "ps" to "10", "order" to "view", "platform" to "web",
                "keyword" to "lofi", "tid" to "3", "type" to "1"
            ),
            filtered.queryParameterNames.associateWith { filtered.queryParameter(it) }
        )
        assertEquals(setOf("media_id", "pn", "ps", "order", "platform"), plain.queryParameterNames)
        assertEquals("mtime", plain.queryParameter("order"))

        assertEquals(FULL_FOLDER_MODEL, page.info)
        assertTrue(page.hasMore)
        assertEquals(
            listOf(
                FavResourceItem(2, 11L, "BV11", "one", "https://i0.hdslb.com/11.jpg", "i", 61, 5L, "U5", 100L, 3L, 1_700_000_000L),
                FavResourceItem(2, 12L, "BV12", "two", "", "", 0, 0L, "", null, null, null),
                FavResourceItem(12, 13L, null, "audio", "", "", 0, 0L, "", null, null, null)
            ),
            page.items
        )
    }

    @Test
    fun `all folder items fetch remaining pages and skip a failed page`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != RESOURCE_LIST_PATH) {
                null
            } else when (request.url.queryParameter("pn")) {
                "1" -> json(resourcePage(45, hasMore = true, 1, 2))
                "2" -> json(resourcePage(45, hasMore = true, 2, 3))
                "3" -> BiliTestReply("""{"code":-500}""", code = 500)
                else -> null
            }
        }
    ) { http, client ->
        val items = client.getAllFavFolderItems(1001L)

        assertEquals(listOf(1L, 2L, 3L), items.map { it.id })
        assertEquals(
            listOf("1", "2", "3"),
            http.requestsTo(RESOURCE_LIST_PATH).mapNotNull { it.url.queryParameter("pn") }.sorted()
        )
        assertEquals(setOf("20"), http.requestsTo(RESOURCE_LIST_PATH).mapNotNull { it.url.queryParameter("ps") }.toSet())
    }

    @Test
    fun `risk control on a folder page stops paging and reports the missing pages`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != RESOURCE_LIST_PATH) {
                null
            } else when (val page = request.url.queryParameter("pn")?.toLong()) {
                1L -> json(resourcePage(160, hasMore = true, 1))
                3L -> BiliTestReply("<html>risk control</html>", code = 412)
                null -> null
                else -> json(resourcePage(160, hasMore = true, page))
            }
        }
    ) { http, client ->
        val firstPage = client.getFavFolderContents(1001L, page = 1, pageSize = 20)

        val result = client.getAllFavFolderItemsResult(1001L, firstPage)

        assertEquals(listOf(1L, 2L, 4L), result.items.map { it.id })
        assertEquals(5, result.missingPages)
        assertFalse(result.isComplete)
        assertEquals(
            listOf("1", "2", "3", "4"),
            http.requestsTo(RESOURCE_LIST_PATH).mapNotNull { it.url.queryParameter("pn") }.sorted()
        )
        assertEquals(listOf(1L, 2L, 4L), client.getAllFavFolderItems(1001L, firstPage).map { it.id })
    }

    @Test
    fun `folder pages run at most three at a time with a gap between chunks`() {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val startedAt = ConcurrentHashMap<String, Long>()
        val finishedAt = ConcurrentHashMap<String, Long>()
        biliClientTest(
            route = { request ->
                val page = request.url.queryParameter("pn")
                if (request.url.encodedPath != RESOURCE_LIST_PATH || page == null) {
                    null
                } else {
                    startedAt[page] = System.nanoTime()
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(40)
                    inFlight.decrementAndGet()
                    finishedAt[page] = System.nanoTime()
                    json(resourcePage(200, hasMore = true, page.toLong()))
                }
            }
        ) { _, client ->
            val firstPage = client.getFavFolderContents(1001L, page = 1, pageSize = 20)

            val result = client.getAllFavFolderItemsResult(1001L, firstPage)

            assertEquals((1L..10L).toList(), result.items.map { it.id })
            assertTrue(result.isComplete)
        }
        assertTrue("max in-flight was ${maxInFlight.get()}", maxInFlight.get() <= 3)
        val chunkGapMs = (startedAt.getValue("5") - listOf("2", "3", "4").maxOf(finishedAt::getValue)) / 1_000_000
        assertTrue("gap between chunks was ${chunkGapMs}ms", chunkGapMs >= 200)
    }

    @Test
    fun `collection archives report pages that failed to load`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != SEASON_ARCHIVES_PATH) {
                null
            } else when (request.url.queryParameter("page_num")) {
                "1" -> json(archivePage(total = 65, """{"aid":1,"bvid":"BV1","title":"a"}"""))
                "2" -> json(archivePage(total = 65, """{"aid":2,"bvid":"BV2","title":"b"}"""))
                else -> BiliTestReply("""{"code":-500}""", code = 500)
            }
        }
    ) { _, client ->
        val result = client.getAllCollectionArchivesResult(mid = 42L, seasonId = 3001L)

        assertEquals(listOf(1L, 2L), result.items.map { it.aid })
        assertEquals(1, result.missingPages)
        assertEquals(listOf(1L, 2L), client.getAllCollectionArchives(mid = 42L, seasonId = 3001L).map { it.aid })
    }

    @Test
    fun `complete first page is returned without further requests`() = biliClientTest(
        route = { null }
    ) { http, client ->
        val firstPage = FavResourcePage(
            info = FULL_FOLDER_MODEL,
            items = listOf(FavResourceItem(2, 1L, "BV1", "one", "", "", 0, 0L, "", null, null, null)),
            hasMore = false
        )

        assertEquals(firstPage.items, client.getAllFavFolderItems(1001L, firstPage))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `collection archives map meta paging and archive stats`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != SEASON_ARCHIVES_PATH) {
                null
            } else when (request.url.queryParameter("season_id")) {
                "3001" -> json(
                    """
                    {"code":0,"data":{
                      "meta":{"season_id":3001,"mid":42,"name":"合集","cover":"//i0.hdslb.com/m.jpg","description":"desc","total":4},
                      "page":{"total":5},
                      "archives":[
                        {"aid":1,"bvid":"BV1","title":"a","pic":"//i0.hdslb.com/1.jpg","duration":60,"pubdate":1700000000,"stat":{"view":99}},
                        "x",
                        {"aid":2,"bvid":"BV2","title":"b"}
                      ]
                    }}
                    """.trimIndent()
                )
                "3002" -> json("""{"code":0}""")
                else -> null
            }
        }
    ) { http, client ->
        val page = client.getCollectionArchives(mid = 42L, seasonId = 3001L, pageSize = 2, sortReverse = true)
        val empty = client.getCollectionArchives(mid = 42L, seasonId = 3002L)

        val request = http.requestsTo(SEASON_ARCHIVES_PATH).first()
        assertEquals("true", request.url.queryParameter("sort_reverse"))
        assertEquals("1", request.url.queryParameter("page_num"))
        assertEquals("2", request.url.queryParameter("page_size"))
        assertEquals("333.999", request.url.queryParameter("web_location"))
        assertEquals(expectedWbiSignature(request), request.url.queryParameter("w_rid"))
        assertEquals(CollectionMeta(3001L, 42L, "合集", "https://i0.hdslb.com/m.jpg", "desc", 5), page.meta)
        assertEquals(
            listOf(
                CollectionArchiveItem(1L, "BV1", "a", "https://i0.hdslb.com/1.jpg", 60, 1_700_000_000L, 99L),
                CollectionArchiveItem(2L, "BV2", "b", "", 0, null, null)
            ),
            page.items
        )
        assertTrue(page.hasMore)

        assertEquals(CollectionMeta(3002L, 42L, "", "", "", 0), empty.meta)
        assertEquals(emptyList<CollectionArchiveItem>(), empty.items)
        assertFalse(empty.hasMore)
    }

    @Test
    fun `all collection archives merge pages and dedupe by bvid or aid`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != SEASON_ARCHIVES_PATH) {
                null
            } else when (request.url.queryParameter("season_id") to request.url.queryParameter("page_num")) {
                "3001" to "1" -> json(archivePage(total = 35, """{"aid":1,"bvid":"BV1","title":"a"}""", """{"aid":2,"bvid":"","title":"b"}"""))
                "3001" to "2" -> json(archivePage(total = 35, """{"aid":2,"bvid":"","title":"b"}""", """{"aid":3,"bvid":"BV3","title":"c"}"""))
                "3002" to "1" -> json(archivePage(total = 1, """{"aid":9,"bvid":"BV9","title":"only"}"""))
                else -> null
            }
        }
    ) { http, client ->
        assertEquals(listOf(1L, 2L, 3L), client.getAllCollectionArchives(mid = 42L, seasonId = 3001L).map { it.aid })
        assertEquals(listOf("BV9"), client.getAllCollectionArchives(mid = 42L, seasonId = 3002L).map { it.bvid })
        assertEquals(3, http.requestsTo(SEASON_ARCHIVES_PATH).size)
        assertNull(http.requestsTo(SEASON_ARCHIVES_PATH).firstOrNull { it.url.queryParameter("page_size") != "30" })
    }

    private fun resourcePage(count: Int, hasMore: Boolean, vararg ids: Long): String {
        val medias = ids.joinToString(",") { id -> """{"type":2,"id":$id,"bvid":"BV$id","title":"item $id"}""" }
        return """{"code":0,"data":{"info":{"id":1001,"title":"默认收藏夹","media_count":$count},"medias":[$medias],"has_more":$hasMore}}"""
    }

    private fun archivePage(total: Int, vararg archives: String): String =
        """{"code":0,"data":{"meta":{"total":$total},"archives":[${archives.joinToString(",")}]}}"""

    private companion object {
        const val CREATED_LIST_ALL_PATH = "/x/v3/fav/folder/created/list-all"
        const val CREATED_LIST_PATH = "/x/v3/fav/folder/created/list"
        const val COLLECTED_LIST_PATH = "/x/v3/fav/folder/collected/list"
        const val FOLDER_INFO_PATH = "/x/v3/fav/folder/info"
        const val RESOURCE_LIST_PATH = "/x/v3/fav/resource/list"
        const val SEASON_ARCHIVES_PATH = "/x/polymer/web-space/seasons_archives_list"

        const val FULL_FOLDER = """{"id":1001,"fid":10,"mid":42,"title":"默认收藏夹","cover":"//i0.hdslb.com/c.jpg","intro":"简介","media_count":12,"cnt_info":{"thumb_up":3,"play":40,"collect":5},"upper":{"mid":42,"name":"UP"},"attr":1,"state":0,"type":11}"""
        const val COLLECTED_FOLDER = """{"id":2001,"title":"他人收藏夹","type":11,"upper":{"mid":7,"name":"别人"}}"""
        const val COLLECTED_SEASON = """{"season_id":3001,"name":"视频合集","total":8,"description":"合集简介","upper":{"mid":8,"name":"合集作者"}}"""

        val FULL_FOLDER_MODEL = FavFolder(
            mediaId = 1001L, fid = 10L, mid = 42L, title = "默认收藏夹", coverUrl = "https://i0.hdslb.com/c.jpg",
            intro = "简介", count = 12, likeCount = 3L, playCount = 40L, collectCount = 5L, upperName = "UP",
            attr = 1, state = 0, itemType = 11
        )
    }
}
