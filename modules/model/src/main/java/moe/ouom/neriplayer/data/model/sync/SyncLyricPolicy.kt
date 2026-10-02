package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.model.SongItem

fun SongItem.hasUserEditedLyricsForSync(): Boolean = lyricSyncEdited == true

fun SongItem.hasSyncLyricText(): Boolean =
    matchedLyric != null || matchedTranslatedLyric != null || matchedRomanizedLyric != null ||
        originalLyric != null || originalTranslatedLyric != null || originalRomanizedLyric != null

fun SyncSong.hasSyncLyricText(): Boolean =
    matchedLyric != null || matchedTranslatedLyric != null || matchedRomanizedLyric != null ||
        originalLyric != null || originalTranslatedLyric != null || originalRomanizedLyric != null

fun SongItem.toLegacyLyricRecoveryCandidateOrNull(): SyncSong? {
    if (lyricSyncEdited != null) return null
    if (!hasSyncLyricText()) return null
    return SyncSong(
        id = id, name = name, artist = artist, album = album, albumId = albumId,
        mediaUri = mediaUri, channelId = channelId, audioId = audioId, subAudioId = subAudioId,
        matchedLyric = matchedLyric, matchedTranslatedLyric = matchedTranslatedLyric,
        matchedRomanizedLyric = matchedRomanizedLyric, matchedLyricSource = matchedLyricSource?.name,
        matchedSongId = matchedSongId, originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric, originalRomanizedLyric = originalRomanizedLyric,
        lyricSyncEdited = null, lyricSyncRevision = 0L
    )
}

fun hasLegacyLyricOverride(
    lyric: String?, translated: String?, romanized: String?,
    original: String?, originalTranslated: String?, originalRomanized: String?, source: String?
): Boolean {
    if (lyric == null && translated == null && romanized == null) return false
    if (source != null) return true
    // 这里只识别需要保留的旧候选，来源和原词差异不能证明是用户编辑
    return lyric != original || translated != originalTranslated || romanized != originalRomanized
}

fun nextLyricSyncRevision(previous: Long, now: Long): Long {
    check(previous < Long.MAX_VALUE) { "Lyric revision exhausted" }
    return maxOf(now, previous.coerceAtLeast(0L) + 1L)
}
