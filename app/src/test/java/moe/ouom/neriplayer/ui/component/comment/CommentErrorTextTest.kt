package moe.ouom.neriplayer.ui.component.comment

import moe.ouom.neriplayer.data.model.comments.CommentError
import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.R as AppR

class CommentErrorTextTest {

    @Test
    fun `every comment error maps to its own localized message`() {
        val expected = mapOf(
            CommentError.NETWORK to AppR.string.comment_error_network,
            CommentError.PERMISSION to AppR.string.comment_error_permission,
            CommentError.NOT_FOUND to AppR.string.comment_error_not_found,
            CommentError.CLOSED to AppR.string.comment_error_closed,
            CommentError.SERVER to AppR.string.comment_error_server,
            CommentError.API to AppR.string.comment_error_unavailable,
            CommentError.UNKNOWN to AppR.string.comment_error_unknown
        )

        assertEquals(CommentError.entries.toSet(), expected.keys)
        expected.forEach { (error, message) ->
            assertEquals(error.name, message, commentErrorTextRes(error))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
