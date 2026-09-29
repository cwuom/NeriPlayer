package moe.ouom.neriplayer.core.comment.repository

import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentSort

internal fun requireNeteaseCommentProgress(result: CommentPage, sort: CommentSort, cursor: String?) {
    val stalledTimeCursor = sort == CommentSort.NEWEST &&
        (result.nextCursor.isNullOrBlank() || result.nextCursor == cursor)
    if (result.hasMore && (result.comments.isEmpty() || stalledTimeCursor)) {
        throw CommentApiException(200, CommentError.API, "NetEase comment pagination did not advance")
    }
}
