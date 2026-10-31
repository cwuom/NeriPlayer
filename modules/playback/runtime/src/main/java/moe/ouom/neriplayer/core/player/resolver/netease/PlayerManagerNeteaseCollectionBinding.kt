package moe.ouom.neriplayer.core.player.resolver.netease

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.url.buildBiliPlaybackAudioInfo
import moe.ouom.neriplayer.core.player.url.buildBiliRepresentationIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBinding
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.server.isServerSong

/**
 * 单曲 / 专辑 / 歌单级 B 站分 P 绑定：用户在详情页手动选好某个多 P 视频后，
 * 这些曲目都直接播放对应分 P，不再走网易云与自动搜索。
 *
 * 绑定解析失败时返回 null，交由原有链路继续，避免因为一条过期绑定而放不出声。
 */
internal suspend fun PlayerManager.tryResolveNeteaseAlbumBindingSource(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult? {
    if (!biliAlbumBindingPriorityEnabled) return null
    val repository = biliCollectionBindingRepo ?: return null

    val matched = runCatching { repository.forSong(song) }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Album binding lookup failed: song=${song.name}, error=${error.message}"
        )
        return null
    } ?: return null

    val (binding, track) = matched
    if (track.cid <= 0L) return null

    val (availableStreams, audioStream) = runCatching {
        biliRepo.getAudioWithDecision(binding.bvid, track.cid)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Album binding stream resolve failed: song=${song.name}, bvid=${binding.bvid}, " +
                "cid=${track.cid}, error=${error.message}"
        )
        return null
    }

    val selectedStream = audioStream?.takeIf { it.url.isNotBlank() } ?: return null

    NPLogger.w(
        "NERI-PlayerManager",
        "Bili collection binding source selected: song=${song.name}, bvid=${binding.bvid}, " +
            "cid=${track.cid}, page=${track.page}, bitrate=${selectedStream.bitrateKbps}"
    )

    return SongUrlResult.Success(
        url = selectedStream.url,
        candidateUrls = selectedStream.candidateUrls,
        durationMs = track.durationSec.takeIf { it > 0 }
            ?.times(1000L)
            ?: song.durationMs.takeIf { it > 0L },
        mimeType = selectedStream.mimeType,
        audioInfo = buildBiliPlaybackAudioInfo(selectedStream, availableStreams) {
            getLocalizedString(it)
        },
        representationIdentity = buildBiliRepresentationIdentity(selectedStream),
        cacheKeyOverride = buildNeteaseAutoBiliCacheKey(
            bvid = binding.bvid,
            cid = track.cid,
            selectedStream = selectedStream
        )
    )
}

/**
 * 绑定保存或删除后让当前播放立刻跟着改。
 *
 * [changed] 是刚存下的绑定，[previous] 是被替换或删掉的旧绑定；只要其中一条牵涉当前这首歌，
 * 就按现有进度重新解析音源。当前音频可能来自网易云（刚加上绑定），也可能来自 B 站（删掉了
 * 正在播的绑定），所以两个来源各排一次刷新，只有与当前来源匹配的那次会真正执行。
 *
 * @return 改动与当前歌曲有关、并已发起刷新时返回 true
 */
internal fun PlayerManager.refreshCurrentSongAfterBiliBindingChangeImpl(
    changed: BiliCollectionBinding?,
    previous: BiliCollectionBinding?
): Boolean {
    if (!biliAlbumBindingPriorityEnabled) return false
    if (changed == null && previous == null) return false
    val song = _currentSongFlow.value ?: return false
    if (isLocalSong(song) || isYouTubeMusicTrack(song) || song.isServerSong()) return false
    if (!listOfNotNull(changed, previous).any { it.affectsSong(song) }) return false

    NPLogger.w(
        "NERI-PlayerManager",
        "Bili collection binding changed, refreshing current song: song=${song.name}, " +
            "changed=${changed?.targetKey}, previous=${previous?.targetKey}"
    )
    scheduleQualityRefresh(
        source = PlaybackAudioSource.NETEASE,
        reason = "bili_collection_binding_changed"
    )
    scheduleQualityRefresh(
        source = PlaybackAudioSource.BILIBILI,
        reason = "bili_collection_binding_changed"
    )
    return true
}

/**
 * 绑定面板保存或解绑后调用，具体判断见 [refreshCurrentSongAfterBiliBindingChangeImpl]。
 *
 * @return 与当前歌曲有关、并已发起刷新时返回 true
 */
fun PlayerManager.refreshCurrentSongAfterBiliBindingChange(
    changed: BiliCollectionBinding? = null,
    previous: BiliCollectionBinding? = null
): Boolean = refreshCurrentSongAfterBiliBindingChangeImpl(changed, previous)
