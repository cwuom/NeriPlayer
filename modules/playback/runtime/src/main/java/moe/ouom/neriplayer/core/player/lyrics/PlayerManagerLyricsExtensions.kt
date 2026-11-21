package moe.ouom.neriplayer.core.player.lyrics

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.lan.LanSharedPresentationStore
import moe.ouom.neriplayer.core.player.lan.isLanTemporaryServerSong
import moe.ouom.neriplayer.core.player.metadata.BiliSubtitleLyricsOptions
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

private fun lanPresentationForLyrics(song: SongItem, useLanPresentation: Boolean) =
    if (useLanPresentation) LanSharedPresentationStore.forSong(song) else null

internal suspend fun PlayerManager.getNeteaseLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseLyrics(songId, neteaseClient, neteaseLyricsCache)
}

internal suspend fun PlayerManager.getNeteaseTranslatedLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseTranslatedLyrics(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getNeteaseRomanizedLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseRomanizedLyrics(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getPreferredNeteaseLyricContentImpl(songId: Long): String {
    return PlayerLyricsProvider.getPreferredNeteaseLyricContent(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getPreferredNeteaseRomanizedLyricContentImpl(songId: Long): String {
    return PlayerLyricsProvider.getPreferredNeteaseRomanizedLyricContent(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getTranslatedLyricsImpl(
    song: SongItem,
    skipPreferredSource: Boolean = false,
    useLanPresentation: Boolean = true,
): List<LyricEntry> {
    lanPresentationForLyrics(song, useLanPresentation)?.let { shared ->
        return shared.translations.map { moe.ouom.neriplayer.core.player.lan.restoreLanLyricLine(it) }
    }
    if (isLanTemporaryServerSong(song)) return emptyList()
    return PlayerLyricsProvider.getTranslatedLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        editableLyricsMatcher = PlayerDependencies.repositories.editableLyricsMatcher,
        preferWordTimedLyrics = preferWordTimedLyrics,
        defaultLyricSource = if (skipPreferredSource) {
            LyricSourcePreference.Automatic
        } else {
            defaultLyricSource
        },
        ytMusicLyricsCache = ytMusicLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG,
        biliClient = biliClient,
        biliSubtitleOptions = BiliSubtitleLyricsOptions(
            enabled = biliCcSubtitleLyricsEnabled,
            preferredLanguage = biliCcSubtitleLanguage,
            aiSubtitleAllowed = biliCcSubtitleAiOnlyEnabled
        )
    )
}

internal suspend fun PlayerManager.getRomanizedLyricsImpl(song: SongItem, useLanPresentation: Boolean = true): List<LyricEntry> {
    lanPresentationForLyrics(song, useLanPresentation)?.let { shared ->
        return shared.romanized.map { moe.ouom.neriplayer.core.player.lan.restoreLanLyricLine(it) }
    }
    if (isLanTemporaryServerSong(song)) return emptyList()
    return PlayerLyricsProvider.getRomanizedLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        editableLyricsMatcher = PlayerDependencies.repositories.editableLyricsMatcher,
        preferWordTimedLyrics = preferWordTimedLyrics,
        defaultLyricSource = defaultLyricSource,
        amllLyricsEnabled = amllLyricsEnabled
    )
}

internal suspend fun PlayerManager.getLyricsImpl(
    song: SongItem,
    skipPreferredSource: Boolean = false,
    useLanPresentation: Boolean = true,
): List<LyricEntry> {
    lanPresentationForLyrics(song, useLanPresentation)?.let { shared ->
        return shared.lyrics.map { moe.ouom.neriplayer.core.player.lan.restoreLanLyricLine(it) }
    }
    if (isLanTemporaryServerSong(song)) return emptyList()
    return PlayerLyricsProvider.getLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        youtubeMusicClient = youtubeMusicClient,
        lrcLibClient = lrcLibClient,
        editableLyricsMatcher = PlayerDependencies.repositories.editableLyricsMatcher,
        amllTtmlClient = amllTtmlClient,
        amllLyricsEnabled = amllLyricsEnabled,
        preferWordTimedLyrics = preferWordTimedLyrics,
        defaultLyricSource = if (skipPreferredSource) {
            LyricSourcePreference.Automatic
        } else {
            defaultLyricSource
        },
        ytMusicLyricsCache = ytMusicLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG,
        biliClient = biliClient,
        biliSubtitleOptions = BiliSubtitleLyricsOptions(
            enabled = biliCcSubtitleLyricsEnabled,
            preferredLanguage = biliCcSubtitleLanguage,
            aiSubtitleAllowed = biliCcSubtitleAiOnlyEnabled
        )
    )
}

internal suspend fun PlayerManager.getPreferredLyricSourceResultImpl(
    song: SongItem,
    preference: LyricSourcePreference
): PreferredLyricSourceResult? {
    if (isLanTemporaryServerSong(song)) return null
    return PlayerLyricsProvider.tryGetPreferredLyricSourceResult(
        song = song,
        preference = preference,
        preferWordTimed = preferWordTimedLyrics,
        editableLyricsMatcher = PlayerDependencies.repositories.editableLyricsMatcher,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
    )
}
