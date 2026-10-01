package moe.ouom.neriplayer.platform.comments.repository

import moe.ouom.neriplayer.platform.comments.CommentApiException
import moe.ouom.neriplayer.platform.comments.mapper.parseNeteaseCommentPage
import moe.ouom.neriplayer.data.model.comments.CommentSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NeteaseCommentPaginationTest {
    private val page = parseNeteaseCommentPage(
        """{"code":200,"more":true,"comments":[{"commentId":1}]}""", 1, 20
    )

    @Test
    fun `newest rejects missing blank and unchanged cursors while more pages remain`() {
        for (nextCursor in listOf(null, "", "  ", "previous")) {
            val error = assertThrows(CommentApiException::class.java) {
                requireNeteaseCommentProgress(page.copy(nextCursor = nextCursor), CommentSort.NEWEST, "previous")
            }
            assertEquals(200, error.code)
        }
    }

    @Test
    fun `nonempty newest page with a different cursor advances`() {
        requireNeteaseCommentProgress(page.copy(nextCursor = "next"), CommentSort.NEWEST, "previous")
    }

    @Test
    fun `numbered sorts do not require time cursors`() {
        requireNeteaseCommentProgress(page, CommentSort.HOT, null)
        requireNeteaseCommentProgress(page, CommentSort.RECOMMENDED, null)
    }

    @Test
    fun `empty intermediate page is rejected for every sort`() {
        for (sort in CommentSort.entries) {
            assertThrows(CommentApiException::class.java) {
                requireNeteaseCommentProgress(page.copy(comments = emptyList(), nextCursor = "next"), sort, "previous")
            }
        }
    }

    @Test
    fun `terminal page may be empty and retain its cursor`() {
        requireNeteaseCommentProgress(
            page.copy(comments = emptyList(), hasMore = false, nextCursor = "previous"),
            CommentSort.NEWEST,
            "previous"
        )
    }
}
