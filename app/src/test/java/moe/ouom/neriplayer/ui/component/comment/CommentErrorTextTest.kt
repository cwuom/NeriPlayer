package moe.ouom.neriplayer.ui.component.comment

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentError
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentErrorTextTest {

    @Test
    fun `every comment error maps to its own localized message`() {
        val expected = mapOf(
            CommentError.NETWORK to CoreCommonR.string.comment_error_network,
            CommentError.PERMISSION to CoreCommonR.string.comment_error_permission,
            CommentError.NOT_FOUND to CoreCommonR.string.comment_error_not_found,
            CommentError.CLOSED to CoreCommonR.string.comment_error_closed,
            CommentError.SERVER to CoreCommonR.string.comment_error_server,
            CommentError.API to CoreCommonR.string.comment_error_unavailable,
            CommentError.UNKNOWN to CoreCommonR.string.comment_error_unknown
        )

        assertEquals(CommentError.entries.toSet(), expected.keys)
        expected.forEach { (error, message) ->
            assertEquals(error.name, message, commentErrorTextRes(error))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
