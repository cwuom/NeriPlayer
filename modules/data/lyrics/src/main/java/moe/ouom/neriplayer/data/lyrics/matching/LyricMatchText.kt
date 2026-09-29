package moe.ouom.neriplayer.data.lyrics.matching

import java.text.Normalizer
import moe.ouom.neriplayer.data.lyrics.search.toSimplifiedChineseForDomesticSearch

private val lyricMatchWhitespaceRegex = Regex("\\s+")

fun normalizeLyricMatchText(value: String): String {
    return Normalizer.normalize(toSimplifiedChineseForDomesticSearch(value), Normalizer.Form.NFKC)
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""\b(feat|ft|featuring)\.?\b"""), " ")
        .replace(Regex("""[(){}\[\]【】（）]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(lyricMatchWhitespaceRegex, " ")
}
