package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

private const val BilingualDisplayTimestampToleranceMs = 50L
private val BilingualDisplayLetterRegex = Regex("""\p{L}""")
private val BilingualDisplayLatinRegex = Regex("""\p{sc=Latin}""")
private val BilingualDisplayHanRegex = Regex("""\p{sc=Han}""")

private enum class BilingualScript {
    Latin,
    Han
}

data class BilingualLyricDisplay(
    val lyrics: List<LyricEntry>,
    val sourceIndices: List<Int>
)

fun prepareBilingualLyricDisplay(lyrics: List<LyricEntry>): BilingualLyricDisplay {
    val displayed = ArrayList<LyricEntry>(lyrics.size)
    val sourceIndices = ArrayList<Int>(lyrics.size)
    var index = 0
    var changed = false
    while (index < lyrics.size) {
        val original = lyrics[index]
        val groupEnd = findDisplayGroupEnd(lyrics, index)
        val entry = if (groupEnd > index + 1) {
            val group = lyrics.subList(index, groupEnd)
            original.copy(
                text = group.joinToString("\n") { it.text.splitBilingualSentence() },
                endTimeMs = group.fold(original.endTimeMs) { endTimeMs, line ->
                    maxOf(endTimeMs, line.endTimeMs)
                },
                translation = group.mergedTranslationsForDisplay()
            )
        } else {
            original.withBilingualLineBreak()
        }
        displayed += entry
        sourceIndices += index
        if (entry !== original) changed = true
        index = groupEnd
    }
    return BilingualLyricDisplay(if (changed) displayed else lyrics, sourceIndices)
}

fun prepareBilingualLyricsForDisplay(lyrics: List<LyricEntry>): List<LyricEntry> =
    prepareBilingualLyricDisplay(lyrics).lyrics

private fun findDisplayGroupEnd(lyrics: List<LyricEntry>, firstIndex: Int): Int {
    val first = lyrics[firstIndex]
    var end = firstIndex + 1
    if (!first.canMergeForDisplay()) return end
    while (end < lyrics.size && belongsToDisplayGroup(first, lyrics[end - 1], lyrics[end])) {
        end++
    }
    return end
}

private fun belongsToDisplayGroup(first: LyricEntry, previous: LyricEntry, next: LyricEntry): Boolean {
    if (!next.canMergeForDisplay()) return false
    val offsetMs = next.startTimeMs - first.startTimeMs
    if (offsetMs !in 0L..BilingualDisplayTimestampToleranceMs) return false
    if (next.startTimeMs == previous.startTimeMs) return true
    val previousScript = previous.text.singleBilingualScript() ?: return false
    val nextScript = next.text.singleBilingualScript() ?: return false
    return previousScript != nextScript
}

private fun LyricEntry.canMergeForDisplay(): Boolean =
    words.isNullOrEmpty() && !isLyricCreditMetadataLine(text)

private fun List<LyricEntry>.mergedTranslationsForDisplay(): String? {
    val translations = mapNotNull { it.translation?.takeIf(String::isNotBlank) }
    return when {
        translations.isNotEmpty() -> translations.joinToString("\n")
        any { it.translation != null } -> ""
        else -> null
    }
}

private fun String.singleBilingualScript(): BilingualScript? {
    var script: BilingualScript? = null
    for (letter in BilingualDisplayLetterRegex.findAll(this)) {
        val nextScript = letter.value.bilingualScript() ?: return null
        if (script != null && script != nextScript) return null
        script = nextScript
    }
    return script
}

private fun LyricEntry.withBilingualLineBreak(): LyricEntry {
    if (!words.isNullOrEmpty() || isLyricCreditMetadataLine(text)) return this
    val displayText = text.splitBilingualSentence()
    return if (displayText == text) this else copy(text = displayText)
}

private fun String.splitBilingualSentence(): String {
    if (contains('\n')) return this
    var script: BilingualScript? = null
    var boundary = -1
    for (letter in BilingualDisplayLetterRegex.findAll(this)) {
        val nextScript = letter.value.bilingualScript() ?: return this
        if (script != null && script != nextScript) {
            if (boundary >= 0) return this
            boundary = letter.range.first
        }
        script = nextScript
    }
    if (boundary < 0) return this
    return substring(0, boundary).trimEnd() + "\n" + substring(boundary)
}

private fun String.bilingualScript(): BilingualScript? = when {
    BilingualDisplayLatinRegex.matches(this) -> BilingualScript.Latin
    BilingualDisplayHanRegex.matches(this) -> BilingualScript.Han
    else -> null
}
