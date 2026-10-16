package moe.ouom.neriplayer.platform.lyrics.matching

import java.text.Normalizer
import moe.ouom.neriplayer.platform.lyrics.search.toSimplifiedChineseForDomesticSearch

private val lyricMatchWhitespaceRegex = Regex("\\s+")

// 现场版在各平台有中英日多种写法，统一成 live 才能参与标题与版本比较
private val lyricMatchLiveMarkerRegex = Regex("""(?:现场|現場|演唱会|演唱會|ライブ|live)版""")
private val lyricMatchCjkLiveMarkerRegex = Regex("""现场|現場|演唱会|演唱會|ライブ""")

fun normalizeLyricMatchText(value: String): String {
    return Normalizer.normalize(toSimplifiedChineseForDomesticSearch(value), Normalizer.Form.NFKC)
        .lowercase()
        .replace(lyricMatchLiveMarkerRegex, " live ")
        .replace(lyricMatchCjkLiveMarkerRegex, " live ")
        .replace("&", " and ")
        .replace(Regex("""\b(feat|ft|featuring)\.?\b"""), " ")
        .replace(Regex("""[(){}\[\]【】（）]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(lyricMatchWhitespaceRegex, " ")
}
