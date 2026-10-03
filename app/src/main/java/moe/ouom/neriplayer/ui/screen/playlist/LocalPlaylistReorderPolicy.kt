package moe.ouom.neriplayer.ui.screen.playlist

import org.burnoutcrew.reorderable.ItemPosition

internal fun localPlaylistCanDragOver(
    canReorderCurrentSongs: () -> Boolean
): (ItemPosition, ItemPosition) -> Boolean = { draggedOver, _ ->
    // 库的第一个参数是候选目标，固定项不能接管正在拖动的歌曲
    canReorderCurrentSongs() &&
        (draggedOver.key as? String) !in LOCAL_PLAYLIST_FIXED_ITEM_KEYS
}

internal fun localPlaylistReorderScrollFraction(
    pointerY: Float,
    visibleStart: Float,
    visibleEnd: Float,
    threshold: Float
): Float {
    if (visibleEnd <= visibleStart || threshold <= 0f) return 0f
    val band = threshold.coerceAtMost((visibleEnd - visibleStart) / 2f)
    return when {
        pointerY < visibleStart + band ->
            -((visibleStart + band - pointerY) / band).coerceIn(0f, 1f)
        pointerY > visibleEnd ->
            ((pointerY - visibleEnd) / band).coerceIn(0f, 1f)
        else -> 0f
    }
}
