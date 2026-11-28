package moe.ouom.neriplayer.data.local.playlist.sort

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.playlist.sort/PlaylistOrderImport
 * Updated: 2026/11/21
 */

import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem

/**
 * 一行自定义顺序。
 *
 * [secondary] 是同一行里的第二个字段，但格式本身不区分「歌名 - 歌手」和「歌手 - 歌名」，
 * 匹配时两种摆法都会尝试，取分数更高的那一种。
 */
data class PlaylistOrderEntry(
    val primary: String,
    val secondary: String? = null
)

data class PlaylistOrderParseResult(
    val entries: List<PlaylistOrderEntry>,
    val skippedLineCount: Int
)

/**
 * 解析用户从文件导入的自定义顺序。
 *
 * 支持的写法：
 * - 每行一首歌名；
 * - `歌名 - 歌手` / `歌手 - 歌名`（`-` 两侧要有空格，`—`、`–`、`|` 不加空格也认）；
 * - `歌名,歌手` 的 csv，首行表头（如 `歌名,歌手`）会被跳过；
 * - 行首序号前缀，如 `1. `、`2、`、`03) `；`12 - 歌名 - 歌手` 这种「序号 + 完整两字段」
 *   也会剥掉序号，但 `1984 - David Bowie` 这类以数字开头的歌名会原样保留。
 *
 * 空行、`#` 注释行和表头行不计入 [PlaylistOrderParseResult.skippedLineCount]。
 */
fun parsePlaylistOrderText(raw: String): PlaylistOrderParseResult {
    val text = raw.removePrefix("\uFEFF")
    if (text.isBlank()) return PlaylistOrderParseResult(emptyList(), 0)
    val lines = text.split('\n').map { it.trim() }
    val meaningful = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
    if (meaningful.isEmpty()) return PlaylistOrderParseResult(emptyList(), 0)

    val csvMode = detectCsvMode(meaningful)
    val entries = ArrayList<PlaylistOrderEntry>(meaningful.size)
    var skipped = 0
    meaningful.forEachIndexed { index, line ->
        val stripped = stripIndexPrefix(line)
        if (stripped.isEmpty()) {
            skipped++
            return@forEachIndexed
        }
        if (csvMode) {
            if (index == 0 && isCsvHeader(stripped)) return@forEachIndexed
            val fields = splitCsvLine(stripped)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (fields.isEmpty()) {
                skipped++
                return@forEachIndexed
            }
            entries += PlaylistOrderEntry(fields[0], fields.getOrNull(1))
            return@forEachIndexed
        }
        val entry = splitLineEntry(stripped)
        if (entry == null) {
            skipped++
        } else {
            entries += entry
        }
    }
    return PlaylistOrderParseResult(entries, skipped)
}

/**
 * 把解析结果匹配到当前歌单，返回完整的新顺序。
 *
 * 未匹配到的条目记入 [PlaylistOrderMatch.unmatchedEntries]；文件里没提到的歌曲
 * 按原顺序追加到末尾，保证结果是当前歌单的一个完整排列，
 * `LocalPlaylistRepository.reorderSongs` 的 `expectedOrder` 校验才能通过。
 */
fun matchPlaylistOrder(
    songs: List<SongItem>,
    entries: List<PlaylistOrderEntry>
): PlaylistOrderMatch {
    if (songs.isEmpty() || entries.isEmpty()) {
        return PlaylistOrderMatch(songs.toList(), 0, entries.map { it.primary })
    }
    val unused = LinkedHashSet(songs.indices.toList())
    val ordered = ArrayList<SongItem>(songs.size)
    val unmatched = ArrayList<String>(0)
    var matchedCount = 0
    entries.forEach { entry ->
        val index = bestMatchIndex(songs, unused, entry)
        if (index == null) {
            unmatched += describePlaylistOrderEntry(entry)
            return@forEach
        }
        unused.remove(index)
        ordered += songs[index]
        matchedCount++
    }
    songs.forEachIndexed { index, song ->
        if (index in unused) ordered += song
    }
    return PlaylistOrderMatch(ordered, matchedCount, unmatched)
}

data class PlaylistOrderMatch(
    val orderedSongs: List<SongItem>,
    val matchedCount: Int,
    val unmatchedEntries: List<String>
) {
    val appendedCount: Int
        get() = orderedSongs.size - matchedCount

    val hasMatch: Boolean
        get() = matchedCount > 0
}

fun describePlaylistOrderEntry(entry: PlaylistOrderEntry): String =
    entry.secondary?.let { "${entry.primary} - $it" } ?: entry.primary

/** `1. `、`2、`、`03) ` 这类明确的序号前缀，`-` 不在其中，见 [DASH_INDEX_PREFIX]。 */
private val INDEX_PREFIX = Regex("""^\s*\d{1,4}\s*[.、．)）:：]\s*""")

/** `12 - 歌名 - 歌手` 的序号形式；只有剩余部分还含分隔符时才算序号。 */
private val DASH_INDEX_PREFIX = Regex("""^\s*\d{1,4}\s*-\s*(.+)""")

/**
 * 字段分隔符。
 *
 * ASCII `-` 必须两侧带空格，否则 `AC-DC`、`B-52's` 这类歌名会被切开；
 * `—`、`–`、`|` 本来就很少出现在歌名里，不加空格也按分隔符处理。
 */
private val DASH_SEPARATOR = Regex("""(?:\s+[-–—]\s+|\s*[—–|｜~～]\s*)""")

/** 表头判定用精确匹配：`Track 2`、`My Song` 这类真实歌名不能被当成表头丢掉。 */
private val CSV_TITLE_HEADER_LABELS = setOf(
    "歌名", "歌曲", "曲名", "标题", "名称", "title", "name", "song", "track", "songname", "trackname"
)
private val CSV_ARTIST_HEADER_LABELS = setOf(
    "歌手", "艺术家", "艺人", "作者", "artist", "singer", "performer", "artistname"
)
private val CSV_TITLE_ONLY_HEADER_LABELS = setOf("歌名", "歌曲", "曲名", "标题")

private fun detectCsvMode(lines: List<String>): Boolean {
    val sample = lines.take(5)
    if (sample.any { it.contains('\t') }) return false
    val first = stripIndexPrefix(sample.first())
    if (isCsvHeader(first)) return true
    val commaLines = sample.count { splitCsvLine(it).size >= 2 }
    // 只有一行 `Hello,Adele` 的局部顺序也是合法 csv：未提到的歌会追加到末尾，
    // 所以不能因为行数少于 2 就退回纯文本，否则歌手字段被丢掉、同名歌会匹配错。
    return commaLines > 0 && commaLines * 2 >= sample.size
}

private fun isCsvHeader(line: String): Boolean {
    val fields = splitCsvLine(line)
        .map { normalizeMatchKey(it) }
        .filter { it.isNotEmpty() }
    if (fields.isEmpty()) return false
    if (fields.size == 1) return fields[0] in CSV_TITLE_ONLY_HEADER_LABELS
    // 两列都必须是表头词，避免「歌名,歌手」以外的普通数据行被误判
    return fields[0] in CSV_TITLE_HEADER_LABELS && fields[1] in CSV_ARTIST_HEADER_LABELS
}

private fun stripIndexPrefix(line: String): String {
    val trimmed = line.trim()
    val withoutBulletIndex = trimmed.replace(INDEX_PREFIX, "").trim()
    if (withoutBulletIndex != trimmed) return withoutBulletIndex
    // `-` 只在后面还有分隔符时才当序号：`1984 - David Bowie` 的 1984 是歌名，
    // 削掉就只剩 `David Bowie`，匹配不到本该命中的那首。
    val dashIndex = DASH_INDEX_PREFIX.find(trimmed) ?: return trimmed
    val remainder = dashIndex.groupValues[1].trim()
    return if (DASH_SEPARATOR.containsMatchIn(remainder)) remainder else trimmed
}

private fun splitLineEntry(line: String): PlaylistOrderEntry? {
    val cleaned = line.trim().trim('"', '\'', '“', '”').trim()
    val tabFields = cleaned.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
    if (tabFields.size >= 2) return PlaylistOrderEntry(tabFields[0], tabFields[1])
    val dashFields = cleaned.split(DASH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    if (dashFields.size >= 2) return PlaylistOrderEntry(dashFields[0], dashFields[1])
    if (cleaned.isEmpty()) return null
    return PlaylistOrderEntry(cleaned)
}

/** 支持双引号包裹字段和 `""` 转义，逗号在引号内不切分。 */
private fun splitCsvLine(line: String): List<String> {
    val fields = ArrayList<String>(2)
    val current = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                fields += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    fields += current.toString()
    return fields
}

private fun bestMatchIndex(
    songs: List<SongItem>,
    candidates: Set<Int>,
    entry: PlaylistOrderEntry
): Int? {
    val primary = normalizeMatchKey(entry.primary)
    if (primary.isEmpty()) return null
    val secondary = entry.secondary?.let(::normalizeMatchKey)?.takeIf { it.isNotEmpty() }
    var bestIndex: Int? = null
    var bestScore = 0
    candidates.forEach { index ->
        val song = songs[index]
        val title = normalizeMatchKey(song.displayName().ifBlank { song.name })
        val artist = normalizeMatchKey(song.displayArtist().ifBlank { song.artist })
        val score = maxOf(
            scorePlaylistOrderOrientation(primary, secondary, title, artist),
            if (secondary == null) 0 else {
                scorePlaylistOrderOrientation(secondary, primary, title, artist)
            }
        )
        // 同分时保留原顺序里更靠前的歌曲，保证结果可预期
        if (score > bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    return bestIndex
}

private fun scorePlaylistOrderOrientation(
    titleNeedle: String,
    artistNeedle: String?,
    title: String,
    artist: String
): Int {
    val titleScore = when {
        titleNeedle.isEmpty() || title.isEmpty() -> 0
        title == titleNeedle -> 40
        title.contains(titleNeedle) -> 30
        title.length >= 2 && titleNeedle.contains(title) -> 20
        else -> 0
    }
    if (titleScore == 0) return 0
    val artistScore = when {
        artistNeedle == null || artist.isEmpty() -> 0
        artist == artistNeedle -> 8
        artist.contains(artistNeedle) || artistNeedle.contains(artist) -> 4
        else -> 0
    }
    return titleScore + artistScore
}

/**
 * 匹配用的归一化：全角转半角、忽略大小写、去掉空格与标点，
 * 让 `晴天 (Live)` 和 `晴天（live）` 视为同一首。
 */
internal fun normalizeMatchKey(value: String): String {
    if (value.isEmpty()) return ""
    val builder = StringBuilder(value.length)
    value.forEach { char ->
        val converted = when {
            char.code in 0xFF01..0xFF5E -> (char.code - 0xFEE0).toChar()
            char == '\u3000' -> ' '
            else -> char
        }
        if (converted.isLetterOrDigit()) builder.append(converted.lowercaseChar())
    }
    return builder.toString()
}
