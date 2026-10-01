package moe.ouom.neriplayer.data.model.comments

enum class CommentSort {
    HOT,
    NEWEST,
    RECOMMENDED;

    companion object {
        fun supportedBy(platform: CommentPlatform): List<CommentSort> = when (platform) {
            CommentPlatform.NETEASE -> entries
            CommentPlatform.BILIBILI -> listOf(HOT, NEWEST)
        }
    }
}
