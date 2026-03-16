package moe.ouom.neriplayer.data

import java.nio.charset.Charset

private val legacyMojibakeCharsets: List<Charset> = buildList {
    runCatching { Charset.forName("GBK") }.getOrNull()?.let(::add)
    runCatching { Charset.forName("GB18030") }.getOrNull()?.let(::add)
}
private const val NUL_CHAR = '\u0000'

internal fun buildSystemPlaylistCandidateNames(
    canonicalChineseName: String,
    canonicalEnglishName: String,
    localizedName: String
): Set<String> {
    return buildSet {
        add(canonicalChineseName)
        add(canonicalEnglishName)
        add(localizedName)
        addAll(generateLegacyMojibakeVariants(canonicalChineseName))
    }
}

private fun generateLegacyMojibakeVariants(sourceName: String): Set<String> {
    if (sourceName.isBlank() || legacyMojibakeCharsets.isEmpty()) return emptySet()

    // 这里只兼容历史上一层 UTF-8 -> ANSI 误解码的脏值，避免把二次/三次乱码继续扩散进源码语义
    return legacyMojibakeCharsets.mapNotNullTo(linkedSetOf()) { charset ->
        runCatching {
            String(sourceName.toByteArray(Charsets.UTF_8), charset)
                .trimEnd(NUL_CHAR)
                .takeIf { it.isNotBlank() && it != sourceName }
        }.getOrNull()
    }
}
