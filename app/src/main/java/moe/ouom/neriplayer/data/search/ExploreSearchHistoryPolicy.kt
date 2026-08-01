package moe.ouom.neriplayer.data.search

import moe.ouom.neriplayer.util.search.SearchTextMatcher

internal const val DEFAULT_EXPLORE_SEARCH_HISTORY_LIMIT = 15

internal fun exploreSearchHistoryForDisplay(
    enabled: Boolean,
    history: List<String>
): List<String> {
    return if (enabled) history else emptyList()
}

internal fun shouldRecordExploreSearchHistory(
    query: String,
    enabled: Boolean
): Boolean {
    return enabled && query.trim().isNotBlank()
}

internal fun exploreSearchHistoryRecordKeyword(
    query: String,
    enabled: Boolean,
    history: List<String>
): String? {
    val normalizedQuery = query.trim()
    if (!shouldRecordExploreSearchHistory(normalizedQuery, enabled)) {
        return null
    }
    return resolveExploreSearchKeyword(
        query = normalizedQuery,
        history = exploreSearchHistoryForDisplay(enabled, history)
    )
}

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

internal fun resolveExploreSearchKeyword(
    query: String,
    history: List<String>
): String {
    val normalizedQuery = query.trim()
    if (normalizedQuery.length < 2) return normalizedQuery

    val bestMatch = history
        .mapNotNull { item ->
            val normalizedItem = item.trim()
            if (normalizedItem.isBlank() || normalizedItem.equals(normalizedQuery, ignoreCase = true)) {
                return@mapNotNull null
            }
            val score = SearchTextMatcher.score(normalizedQuery, listOf(normalizedItem))
                ?: return@mapNotNull null
            normalizedItem to score
        }
        .filter { (_, score) -> score <= HISTORY_SEARCH_ALIAS_MAX_SCORE }
        .minWithOrNull(
            compareBy<Pair<String, Int>> { it.second }
                .thenBy { it.first.length }
        )
        ?.first

    return bestMatch ?: normalizedQuery
}

private const val HISTORY_SEARCH_ALIAS_MAX_SCORE = 12
