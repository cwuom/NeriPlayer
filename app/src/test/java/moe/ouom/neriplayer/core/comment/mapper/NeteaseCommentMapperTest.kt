package moe.ouom.neriplayer.core.comment.mapper

import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 网易云评论 JSON -> 统一评论模型 的单元测试。
 */
class NeteaseCommentMapperTest {

    /**
     * 调用网易云解析器并断言抛出 CommentApiException，返回异常用于核对错误码与原因。
     */
    private fun parseError(json: String): CommentApiException {
        try {
            parseNeteaseCommentPage(json, page = 1, pageSize = 20)
        } catch (error: CommentApiException) {
            return error
        }
        fail("expected CommentApiException")
        error("unreachable")
    }

    /**
     * 正常响应：解析评论与 total/more，毫秒时间戳原样保留，userId/avatarUrl 为 0 或空时置 null。
     */
    @Test
    fun `parses comments, total and more flag`() {
        val page = parseNeteaseCommentPage(
            rawJson = """
                {
                  "code": 200,
                  "total": 3,
                  "more": true,
                  "comments": [
                    {
                      "commentId": 111,
                      "content": "好听",
                      "likedCount": 12,
                      "replyCount": 2,
                      "time": 1700000000000,
                      "user": {
                        "userId": 9,
                        "nickname": "小明",
                        "avatarUrl": "https://p1.music.126.net/a.jpg",
                        "level": 6
                      }
                    },
                    {
                      "commentId": 222,
                      "content": "second",
                      "likedCount": 0,
                      "time": 1700000000001,
                      "user": { "userId": 0, "nickname": "无名", "avatarUrl": "", "level": 0 }
                    }
                  ]
                }
            """.trimIndent(),
            page = 1,
            pageSize = 20
        )

        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
        assertEquals(3L, page.total)
        assertTrue(page.hasMore)
        assertEquals(2, page.comments.size)

        val first = page.comments[0]
        assertEquals("111", first.id)
        assertEquals("9", first.userId)
        assertEquals("小明", first.username)
        assertEquals("https://p1.music.126.net/a.jpg", first.avatarUrl)
        assertEquals("好听", first.content)
        assertEquals(12L, first.likeCount)
        assertEquals(2L, first.replyCount)
        assertEquals(1700000000000L, first.createTime)
        assertEquals(CommentPlatform.NETEASE, first.platform)
        assertEquals(6, first.userLevel)

        val second = page.comments[1]
        assertEquals("222", second.id)
        assertNull(second.userId)
        assertNull(second.avatarUrl)
        assertNull(second.replyCount)
        assertNull(second.userLevel)
        assertEquals(0L, second.likeCount)
        assertEquals(1700000000001L, second.createTime)
    }

    /**
     * 回复数取值优先级：顶层 replyCount 优先（0 也算有效值），
     * 缺失时回退 showFloorComment.replyCount，两处都没有则为 null。
     */
    @Test
    fun `reply count falls back to showFloorComment`() {
        val page = parseNeteaseCommentPage(
            rawJson = """
                {
                  "code": 200,
                  "comments": [
                    { "commentId": 1, "showFloorComment": { "replyCount": 5 } },
                    { "commentId": 2, "replyCount": 0, "showFloorComment": { "replyCount": 9 } },
                    { "commentId": 3, "showFloorComment": { "replyCount": 0 } },
                    { "commentId": 4, "showFloorComment": {} },
                    { "commentId": 5 }
                  ]
                }
            """.trimIndent(),
            page = 1,
            pageSize = 20
        )

        // 顶层缺失时用 showFloorComment.replyCount
        assertEquals(5L, page.comments[0].replyCount)
        // 顶层存在时优先用顶层（0 也是有效值）
        assertEquals(0L, page.comments[1].replyCount)
        assertEquals(0L, page.comments[2].replyCount)
        // 两处都没有 -> null（UI 隐藏该字段，而不是显示 0 条回复）
        assertNull(page.comments[3].replyCount)
        assertNull(page.comments[4].replyCount)
    }

    /**
     * comments 为空数组时得到空页：total 为 null、hasMore 为 false。
     */
    @Test
    fun `empty comment array produces an empty page`() {
        val page = parseNeteaseCommentPage("""{"code":200,"comments":[]}""", page = 1, pageSize = 20)

        assertTrue(page.comments.isEmpty())
        assertNull(page.total)
        assertEquals(false, page.hasMore)
    }

    /**
     * 响应缺少 comments 字段时不崩溃，返回空页且 total 为 null、hasMore 为 false。
     */
    @Test
    fun `missing comment array does not crash`() {
        val page = parseNeteaseCommentPage("""{"code":200}""", page = 1, pageSize = 20)

        assertTrue(page.comments.isEmpty())
        assertNull(page.total)
        assertEquals(false, page.hasMore)
    }

    /**
     * more 字段缺失时按 total 与翻页位置推断 hasMore：第 1 页为 true，第 5 页（已到末尾）为 false。
     */
    @Test
    fun `hasMore falls back to total when the more flag is absent`() {
        val first = parseNeteaseCommentPage(
            """{"code":200,"total":100,"comments":[{"commentId":1}]}""",
            page = 1,
            pageSize = 20
        )
        assertTrue(first.hasMore)

        val last = parseNeteaseCommentPage(
            """{"code":200,"total":100,"comments":[{"commentId":1}]}""",
            page = 5,
            pageSize = 20
        )
        assertEquals(false, last.hasMore)
    }

    /**
     * total 未知且 more 缺失时按本页条数推断 hasMore：满页为 true，不满页为 false。
     */
    @Test
    fun `hasMore falls back to page size when total is unknown`() {
        val full = (1..20).joinToString(",") { """{"commentId":$it}""" }
        val fullPage = parseNeteaseCommentPage(
            """{"code":200,"comments":[$full]}""",
            page = 1,
            pageSize = 20
        )
        assertTrue(fullPage.hasMore)

        val partialPage = parseNeteaseCommentPage(
            """{"code":200,"comments":[{"commentId":1},{"commentId":2}]}""",
            page = 1,
            pageSize = 20
        )
        assertEquals(false, partialPage.hasMore)
    }

    /**
     * 显式 more 字段优先于 total（total=1000 但 more=false 时 hasMore 为 false）。
     */
    @Test
    fun `explicit more flag wins over total`() {
        val page = parseNeteaseCommentPage(
            """{"code":200,"total":1000,"more":false,"comments":[{"commentId":1}]}""",
            page = 1,
            pageSize = 20
        )
        assertEquals(false, page.hasMore)
    }

    /**
     * code 非 200 时抛异常并映射原因：403→PERMISSION、404→NOT_FOUND、503→SERVER、250→API。
     */
    @Test
    fun `non 200 code throws with the mapped reason`() {
        val permission = parseError("""{"code":403,"message":"forbidden"}""")
        assertEquals(403, permission.code)
        assertEquals(CommentError.PERMISSION, permission.reason)

        val notFound = parseError("""{"code":404}""")
        assertEquals(CommentError.NOT_FOUND, notFound.reason)

        val server = parseError("""{"code":503}""")
        assertEquals(CommentError.SERVER, server.reason)

        val other = parseError("""{"code":250}""")
        assertEquals(CommentError.API, other.reason)
    }

    /**
     * 错误码映射表逐项校验，含 301/401/-460 也归为 PERMISSION、599 归为 SERVER。
     */
    @Test
    fun `error code mapping table`() {
        assertEquals(CommentError.PERMISSION, neteaseCommentError(301))
        assertEquals(CommentError.PERMISSION, neteaseCommentError(401))
        assertEquals(CommentError.PERMISSION, neteaseCommentError(403))
        assertEquals(CommentError.PERMISSION, neteaseCommentError(-460))
        assertEquals(CommentError.NOT_FOUND, neteaseCommentError(404))
        assertEquals(CommentError.SERVER, neteaseCommentError(500))
        assertEquals(CommentError.SERVER, neteaseCommentError(599))
        assertEquals(CommentError.API, neteaseCommentError(0))
        assertEquals(CommentError.API, neteaseCommentError(-1))
    }
}
