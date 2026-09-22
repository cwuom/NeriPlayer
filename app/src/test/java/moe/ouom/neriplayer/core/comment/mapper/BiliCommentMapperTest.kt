package moe.ouom.neriplayer.core.comment.mapper

import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bilibili 评论 JSON -> 统一评论模型 的单元测试。
 */
class BiliCommentMapperTest {

    private fun parseError(json: String): CommentApiException {
        try {
            parseBiliCommentPage(JSONObject(json), page = 1, pageSize = 20)
        } catch (error: CommentApiException) {
            return error
        }
        fail("expected CommentApiException")
        error("unreachable")
    }

    @Test
    fun `parses replies, total and normalises ctime to milliseconds`() {
        val page = parseBiliCommentPage(
            JSONObject(
                """
                {
                  "code": 0,
                  "data": {
                    "page": { "count": 42 },
                    "replies": [
                      {
                        "rpid": 555,
                        "like": 7,
                        "rcount": 2,
                        "ctime": 1700000000,
                        "content": { "message": "前排" },
                        "member": {
                          "mid": "12345",
                          "uname": "UP主",
                          "avatar": "//i1.hdslb.com/bfs/face/abc.jpg",
                          "level_info": { "current_level": 5 }
                        }
                      },
                      {
                        "rpid": 666,
                        "like": 0,
                        "count": 3,
                        "ctime": 1700000001,
                        "content": { "message": "second" },
                        "member": {
                          "mid": "",
                          "uname": "路人",
                          "avatar": "https://example.com/a.png"
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
            page = 1,
            pageSize = 20
        )

        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
        assertEquals(42L, page.total)
        assertTrue(page.hasMore)
        assertEquals(2, page.comments.size)

        val first = page.comments[0]
        assertEquals("555", first.id)
        assertEquals("12345", first.userId)
        assertEquals("UP主", first.username)
        assertEquals("前排", first.content)
        assertEquals(7L, first.likeCount)
        assertEquals(2L, first.replyCount)
        // ctime 是秒, 归一化为毫秒
        assertEquals(1700000000000L, first.createTime)
        assertEquals(CommentPlatform.BILIBILI, first.platform)
        assertEquals(5, first.userLevel)
        // // 开头的头像补协议头, 并追加尺寸参数
        assertTrue(first.avatarUrl.orEmpty().startsWith("https://i1.hdslb.com/bfs/face/abc.jpg@96w_96h"))

        val second = page.comments[1]
        assertEquals("666", second.id)
        assertNull(second.userId)
        assertEquals("路人", second.username)
        assertEquals(3L, second.replyCount)
        assertNull(second.userLevel)
        // 非 hdslb / biliimg 域名原样返回
        assertEquals("https://example.com/a.png", second.avatarUrl)
    }

    @Test
    fun `null data means the video has no comments`() {
        val page = parseBiliCommentPage(
            JSONObject("""{"code":0,"data":null}"""),
            page = 1,
            pageSize = 20
        )

        assertTrue(page.comments.isEmpty())
        assertNull(page.total)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `missing replies does not crash`() {
        val page = parseBiliCommentPage(
            JSONObject("""{"code":0,"data":{"page":{"count":0}}}"""),
            page = 1,
            pageSize = 20
        )

        assertTrue(page.comments.isEmpty())
        assertEquals(0L, page.total)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `hasMore falls back to page size when total is unknown`() {
        val replies = (1..20).joinToString(",") { """{"rpid":$it}""" }
        val fullPage = parseBiliCommentPage(
            JSONObject("""{"code":0,"data":{"replies":[$replies]}}"""),
            page = 1,
            pageSize = 20
        )
        assertTrue(fullPage.hasMore)

        val partialPage = parseBiliCommentPage(
            JSONObject("""{"code":0,"data":{"replies":[{"rpid":1},{"rpid":2}]}}"""),
            page = 1,
            pageSize = 20
        )
        assertEquals(false, partialPage.hasMore)
    }

    @Test
    fun `reply closed code is reported as closed`() {
        val error = parseError("""{"code":12061,"message":"评论区已关闭"}""")

        assertEquals(12061, error.code)
        assertEquals(CommentError.CLOSED, error.reason)
    }

    @Test
    fun `non zero code throws with the mapped reason`() {
        assertEquals(CommentError.PERMISSION, parseError("""{"code":-403}""").reason)
        assertEquals(CommentError.NOT_FOUND, parseError("""{"code":-404}""").reason)
        assertEquals(CommentError.SERVER, parseError("""{"code":500}""").reason)
        assertEquals(CommentError.API, parseError("""{"code":-1}""").reason)
    }

    @Test
    fun `error code mapping table`() {
        assertEquals(CommentError.PERMISSION, biliCommentError(-403))
        assertEquals(CommentError.PERMISSION, biliCommentError(-412))
        assertEquals(CommentError.NOT_FOUND, biliCommentError(-404))
        assertEquals(CommentError.CLOSED, biliCommentError(12061))
        assertEquals(CommentError.SERVER, biliCommentError(503))
        assertEquals(CommentError.API, biliCommentError(-1))
        assertEquals(CommentError.API, biliCommentError(0))
    }
}
