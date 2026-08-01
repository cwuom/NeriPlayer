package moe.ouom.neriplayer.data.search

internal const val DEFAULT_EXPLORE_SEARCH_HISTORY_LIMIT = 12

internal fun updatedExploreSearchHistory(
    current: List<String>,
    query: String,
    limit: Int = DEFAULT_EXPLORE_SEARCH_HISTORY_LIMIT
): List<String> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank() || limit <= 0) {
        return current.take(limit.coerceAtLeast(0))
    }

    return buildList {
        add(normalizedQuery)
        current.forEach { item ->
            val normalizedItem = item.trim()
            if (
                normalizedItem.isNotBlank() &&
                !normalizedItem.equals(normalizedQuery, ignoreCase = true) &&
                none { it.equals(normalizedItem, ignoreCase = true) }
            ) {
                add(normalizedItem)
            }
        }
    }.take(limit)
}
