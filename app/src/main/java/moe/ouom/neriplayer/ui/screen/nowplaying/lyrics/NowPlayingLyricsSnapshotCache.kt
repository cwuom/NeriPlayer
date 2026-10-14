package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

internal data class NowPlayingLyricsSnapshotKey(
    val songKey: String,
    val lyrics: NowPlayingLyricsReloadKey,
    val name: String,
    val artist: String,
    val durationMs: Long,
    val mediaUrlReady: Boolean,
    val preferWordTimedLyrics: Boolean,
    val source: LyricSourcePreference,
    val versions: NowPlayingLyricsRefreshVersions,
    val cacheGeneration: Long
)

internal fun nowPlayingLyricsSnapshotKey(
    request: NowPlayingLyricsLoadRequest,
    versions: NowPlayingLyricsRefreshVersions,
    cacheGeneration: Long
): NowPlayingLyricsSnapshotKey? {
    // 本地文件需沿用每次打开时的侧载和嵌入歌词检查
    val song = request.song?.takeUnless { it.isLocalSong() } ?: return null
    return NowPlayingLyricsSnapshotKey(song.stableKey(), checkNotNull(nowPlayingLyricsReloadKey(song)),
        song.name, song.artist, song.durationMs,
        // 歌词只依赖播放地址是否就绪，页面关闭期间地址重新解析或签名刷新不能让快照失效
        !request.currentMediaUrl.isNullOrBlank(),
        request.preferWordTimedLyrics, request.defaultLyricSource, versions, cacheGeneration)
}

internal data class NowPlayingLyricsSnapshot(
    val key: NowPlayingLyricsSnapshotKey,
    val state: LoadedLyricsState,
    val secondaryResolved: Boolean,
    val loadedAtNanos: Long
)

internal class NowPlayingLyricsSnapshotCache(
    private val clockNanos: () -> Long = System::nanoTime
) {
    private var snapshot: NowPlayingLyricsSnapshot? = null

    fun find(key: NowPlayingLyricsSnapshotKey?): NowPlayingLyricsSnapshot? =
        snapshot?.takeIf { it.key == key }

    fun reusable(key: NowPlayingLyricsSnapshotKey?): NowPlayingLyricsSnapshot? {
        val cached = find(key) ?: return null
        if (!cached.secondaryResolved || cached.state.lyrics.isEmpty()) return null
        // 分轨失败目前会返回空列表，定期重查以保留恢复机会
        return cached.takeIf { clockNanos() - it.loadedAtNanos < TimeUnit.MINUTES.toNanos(1) }
    }

    fun publish(key: NowPlayingLyricsSnapshotKey, state: LoadedLyricsState, secondaryResolved: Boolean) {
        if (!state.hasDisplayableContent()) return
        if (find(key)?.secondaryResolved == true && !secondaryResolved) return
        snapshot = NowPlayingLyricsSnapshot(key, state, secondaryResolved, clockNanos())
    }
}
