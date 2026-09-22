package moe.ouom.neriplayer.core.player.service

internal object XiaomiSuperIslandLyricLayout {
    data class Split(val left: String, val right: String)

    fun takeByWeight(text: String, maxWeight: Int): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""
        var weight = 0
        var end = 0
        for ((index, character) in normalized.withIndex()) {
            val nextWeight = weight + character.weight()
            if (nextWeight > maxWeight) break
            weight = nextWeight
            end = index + 1
        }
        return normalized.substring(0, end).trim()
    }

    fun splitFullLyric(
        text: String,
        showLeftCover: Boolean,
        leftMaxWeight: Int,
        rightMaxWeight: Int
    ): Split {
        val normalized = text.trim()
        if (normalized.isEmpty()) return Split("", "")
        val characters = normalized.toList()
        val leftVisualNumerator = if (showLeftCover) 6 else 5
        val leftVisualDenominator = if (showLeftCover) 5 else 6
        var best: Candidate? = null

        for (endIndex in characters.size downTo 2) {
            for (splitIndex in 1 until endIndex) {
                val left = characters.subList(0, splitIndex).joinToString("").trim()
                val right = characters.subList(splitIndex, endIndex).joinToString("").trim()
                val leftWeight = weightOf(left)
                val rightWeight = weightOf(right)
                if (leftWeight > leftMaxWeight || rightWeight > rightMaxWeight) continue
                val leftVisualWeight =
                    (leftWeight * leftVisualNumerator + leftVisualDenominator / 2) /
                        leftVisualDenominator
                val score = kotlin.math.abs(leftVisualWeight - rightWeight) * 10 +
                    (leftMaxWeight + rightMaxWeight - leftWeight - rightWeight) +
                    if (endIndex == characters.size) 0 else (characters.size - endIndex) * 30
                if (best == null || score < best.score) {
                    best = Candidate(splitIndex, endIndex, score)
                }
            }
        }

        val candidate = best
        if (candidate == null) {
            val left = takeByWeight(normalized, leftMaxWeight)
            val right = takeByWeight(normalized.drop(left.length).trimStart(), rightMaxWeight)
            return Split(left, right)
        }
        return Split(
            left = characters.subList(0, candidate.splitIndex).joinToString("").trim(),
            right = characters.subList(candidate.splitIndex, candidate.endIndex).joinToString("").trim()
        )
    }

    fun weightForCharacters(characters: Int): Int = characters.coerceAtLeast(1) * 2

    private data class Candidate(val splitIndex: Int, val endIndex: Int, val score: Int)

    private fun weightOf(text: String): Int = text.sumOf { it.weight() }

    private fun Char.weight(): Int {
        if (isWhitespace()) return 0
        return when (Character.UnicodeBlock.of(this)) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES -> 2
            null -> 1
            else -> 1
        }
    }
}
