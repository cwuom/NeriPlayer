package moe.ouom.neriplayer.core.player.service.car.library

internal object CarLibraryPaging {
    const val PAGE_SIZE = 100

    fun isValidRange(start: Int, end: Int, total: Int): Boolean = start >= 0 && end > start && end <= total

    fun pages(directoryId: String, start: Int, end: Int): List<CarLibraryItem> {
        val span = pageSpan(end - start)
        return buildList {
            var offset = start
            while (offset < end) {
                val pageEnd = (offset.toLong() + span).coerceAtMost(end.toLong()).toInt()
                add(item(directoryId, offset, pageEnd))
                offset = pageEnd
            }
        }
    }

    fun item(directoryId: String, start: Int, end: Int): CarLibraryItem = CarLibraryItem(
        mediaId = CarMediaIds.page(directoryId, start, end),
        title = "${start + 1} - $end"
    )

    private fun pageSpan(count: Int): Long {
        var span = PAGE_SIZE.toLong()
        while ((count.toLong() + span - 1) / span > PAGE_SIZE) span *= PAGE_SIZE
        return span
    }
}
