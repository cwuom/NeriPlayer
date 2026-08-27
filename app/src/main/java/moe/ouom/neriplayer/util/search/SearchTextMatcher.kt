package moe.ouom.neriplayer.util.search

import com.github.promeg.pinyinhelper.Pinyin
import java.text.Normalizer
import java.util.Locale

object SearchTextMatcher {
    class Value internal constructor(
        val raw: Any?,
        val bias: Int
    )

    fun value(raw: Any?, bias: Int = 0): Value = Value(raw = raw, bias = bias)

    fun matches(query: String, vararg values: Any?): Boolean {
        return score(query, values.asIterable()) != null
    }

    fun matches(query: String, values: Iterable<Any?>): Boolean {
        return score(query, values) != null
    }

    fun score(query: String, values: Iterable<Any?>): Int? {
        val queryTokens = query.searchQueryTokens()
        if (queryTokens.isEmpty()) return 0
        return scoreCandidates(queryTokens, collapseCandidates(values))
    }

    fun tokensOf(value: String): List<String> {
        return value.searchCandidates().map { it.text }.distinct()
    }

    /**
     * keeps the expensive candidate and pinyin generation out of the query path
     */
    class Index<T> internal constructor(
        private val items: List<T>,
        private val candidates: List<List<SearchCandidate>>
    ) {
        fun filterAndRank(query: String): List<T> {
            val normalizedQuery = query.trim()
            if (normalizedQuery.isBlank()) return items

            val queryTokens = normalizedQuery.searchQueryTokens()
            if (queryTokens.isEmpty()) return items

            return items.mapIndexedNotNull { index, item ->
                val score = SearchTextMatcher.scoreCandidates(queryTokens, candidates[index])
                    ?: return@mapIndexedNotNull null
                RankedSearchItem(item = item, score = score, index = index)
            }
                .sortedWith(
                    compareBy<RankedSearchItem<T>> { it.score }
                        .thenBy { it.index }
                )
                .map { it.item }
        }
    }

    fun <T> index(
        items: List<T>,
        tokens: (T) -> Iterable<Any?>
    ): Index<T> {
        val indexedCandidates = items.map { item ->
            collapseCandidates(tokens(item))
        }
        return Index(items = items, candidates = indexedCandidates)
    }

    fun <T> filterAndRank(
        query: String,
        items: List<T>,
        tokens: (T) -> Iterable<Any?>
    ): List<T> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return items
        val queryTokens = normalizedQuery.searchQueryTokens()
        if (queryTokens.isEmpty()) return items

        return items
            .mapIndexedNotNull { index, item ->
                val score = scoreCandidates(
                    queryTokens = queryTokens,
                    candidates = collapseCandidates(tokens(item))
                ) ?: return@mapIndexedNotNull null
                RankedSearchItem(item = item, score = score, index = index)
            }
            .sortedWith(
                compareBy<RankedSearchItem<T>> { it.score }
                    .thenBy { it.index }
            )
            .map { it.item }
    }

    private fun scoreCandidates(
        queryTokens: List<String>,
        candidates: List<SearchCandidate>
    ): Int? {
        if (candidates.isEmpty()) return null

        var totalScore = 0
        for (queryToken in queryTokens) {
            val bestScore = candidates
                .mapNotNull { candidate -> matchScore(queryToken, candidate) }
                .minOrNull()
                ?: return null
            totalScore += bestScore
        }
        return totalScore
    }

    private fun collapseCandidates(values: Iterable<Any?>): List<SearchCandidate> {
        val minimumBiasByText = LinkedHashMap<String, Int>()
        values.forEach { value ->
            value.searchCandidates().forEach { candidate ->
                val previousBias = minimumBiasByText[candidate.text]
                if (previousBias == null || candidate.bias < previousBias) {
                    minimumBiasByText[candidate.text] = candidate.bias
                }
            }
        }
        return minimumBiasByText.map { (text, bias) ->
            SearchCandidate(text = text, bias = bias)
        }
    }
}

private data class RankedSearchItem<T>(
    val item: T,
    val score: Int,
    val index: Int
)

internal data class SearchCandidate(
    val text: String,
    val bias: Int
)

private data class PinyinToken(
    val full: String,
    val initials: String
)

private fun Any?.searchCandidates(baseBias: Int = 0): List<SearchCandidate> {
    return when (this) {
        null -> emptyList()
        is SearchTextMatcher.Value -> raw.searchCandidates(baseBias + bias)
        is Iterable<*> -> flatMap { it.searchCandidates(baseBias) }
        else -> toString().candidateTokens(baseBias)
    }
}

private fun String.searchQueryTokens(): List<String> {
    return normalizeSearchText(this)
        .split(SearchSeparatorRegex)
        .filter { it.isNotBlank() }
}

private fun String.candidateTokens(baseBias: Int): List<SearchCandidate> {
    val normalized = normalizeSearchText(this)
    if (normalized.isBlank()) return emptyList()

    val splitTokens = normalizeSearchTextPreservingCase(this)
        .split(SearchSeparatorRegex)
        .filter { it.isNotBlank() }
        .flatMap { splitCamelToken(it) }
    val compact = splitTokens.joinToString("")
    val acronym = splitTokens
        .filter { it.isNotBlank() }
        .joinToString("") { it.first().toString() }

    return buildList {
        add(SearchCandidate(text = normalized, bias = baseBias + WHOLE_TEXT_BIAS))
        splitTokens.forEach { token ->
            add(SearchCandidate(text = token, bias = baseBias + SPLIT_TOKEN_BIAS))
            token.toPinyinToken()?.let { pinyin ->
                add(SearchCandidate(text = pinyin.full, bias = baseBias + PINYIN_FULL_BIAS))
                if (pinyin.initials.length > 1) {
                    add(SearchCandidate(text = pinyin.initials, bias = baseBias + PINYIN_INITIALS_BIAS))
                }
            }
        }
        if (compact.isNotBlank()) {
            add(SearchCandidate(text = compact, bias = baseBias + COMPACT_TOKEN_BIAS))
            compact.toPinyinToken()?.let { pinyin ->
                add(SearchCandidate(text = pinyin.full, bias = baseBias + COMPACT_PINYIN_FULL_BIAS))
                if (pinyin.initials.length > 1) {
                    add(SearchCandidate(text = pinyin.initials, bias = baseBias + COMPACT_PINYIN_INITIALS_BIAS))
                }
            }
        }
        if (acronym.length > 1) {
            add(SearchCandidate(text = acronym, bias = baseBias + ACRONYM_BIAS))
        }
    }.distinctBy { it.text to it.bias }
}

private fun matchScore(query: String, candidate: SearchCandidate): Int? {
    val text = candidate.text
    return when {
        query == text -> candidate.bias
        text.startsWith(query) -> {
            PREFIX_MATCH_SCORE + candidate.bias + (text.length - query.length)
        }
        text.contains(query) -> {
            CONTAINS_MATCH_SCORE + candidate.bias + (text.indexOf(query) * 2)
        }
        query.length > 1 -> {
            text.subsequenceGapPenalty(query)?.let { gapPenalty ->
                if (!allowsFuzzySubsequence(query, text, gapPenalty)) return null
                FUZZY_MATCH_SCORE + candidate.bias + gapPenalty * 4 + (text.length - query.length)
            }
        }
        else -> null
    }
}

private fun allowsFuzzySubsequence(query: String, text: String, gapPenalty: Int): Boolean {
    if (!query.isAsciiLetterOrDigitToken() || !text.isAsciiLetterOrDigitToken()) {
        return true
    }
    if (query.firstOrNull() != text.firstOrNull()) {
        return false
    }
    return gapPenalty <= maxOf(1, query.length)
}

private fun String.subsequenceGapPenalty(query: String): Int? {
    var queryIndex = 0
    var startIndex = -1
    var endIndex = -1
    forEachIndexed { index, char ->
        if (queryIndex < query.length && char == query[queryIndex]) {
            if (startIndex < 0) startIndex = index
            endIndex = index
            queryIndex += 1
        }
    }
    if (queryIndex != query.length || startIndex < 0 || endIndex < 0) return null
    return (endIndex - startIndex + 1) - query.length
}

private fun String.isAsciiLetterOrDigitToken(): Boolean {
    return isNotEmpty() && all { it in 'a'..'z' || it in '0'..'9' }
}

private fun splitCamelToken(value: String): List<String> {
    if (value.length <= 1) return listOf(value)
    val result = mutableListOf<String>()
    var start = 0
    for (index in 1 until value.length) {
        val previous = value[index - 1]
        val current = value[index]
        if (previous.isLowerCase() && current.isUpperCase()) {
            result += value.substring(start, index).lowercase(Locale.ROOT)
            start = index
        }
    }
    result += value.substring(start).lowercase(Locale.ROOT)
    return result
}

private fun normalizeSearchText(value: String): String {
    return normalizeSearchText(value, lowercase = true)
}

private fun normalizeSearchTextPreservingCase(value: String): String {
    return normalizeSearchText(value, lowercase = false)
}

private fun normalizeSearchText(value: String, lowercase: Boolean): String {
    val folded = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
    return buildString(folded.length) {
        folded.forEach { char ->
            when {
                char.category == CharCategory.NON_SPACING_MARK -> Unit
                char == '\u3000' -> append(' ')
                lowercase -> append(char.lowercaseChar())
                else -> append(char)
            }
        }
    }
}

private fun String.toPinyinToken(): PinyinToken? {
    val full = StringBuilder(length * 2)
    val initials = StringBuilder(length)
    var hasChinese = false

    forEach { char ->
        when {
            Pinyin.isChinese(char) -> {
                val token = Pinyin.toPinyin(char).lowercase(Locale.ROOT)
                if (token.isNotBlank()) {
                    full.append(token)
                    initials.append(token.first())
                    hasChinese = true
                }
            }
            char.isLetterOrDigit() -> {
                val normalizedChar = char.lowercaseChar()
                full.append(normalizedChar)
                initials.append(normalizedChar)
            }
        }
    }

    if (!hasChinese || full.isBlank()) return null
    return PinyinToken(full = full.toString(), initials = initials.toString())
}

private val SearchSeparatorRegex = Regex("[^\\p{L}\\p{Nd}]+")

private const val WHOLE_TEXT_BIAS = 0
private const val SPLIT_TOKEN_BIAS = 2
private const val COMPACT_TOKEN_BIAS = 4
private const val PINYIN_FULL_BIAS = 6
private const val COMPACT_PINYIN_FULL_BIAS = 8
private const val ACRONYM_BIAS = 10
private const val PINYIN_INITIALS_BIAS = 12
private const val COMPACT_PINYIN_INITIALS_BIAS = 14

private const val PREFIX_MATCH_SCORE = 16
private const val CONTAINS_MATCH_SCORE = 48
private const val FUZZY_MATCH_SCORE = 96
