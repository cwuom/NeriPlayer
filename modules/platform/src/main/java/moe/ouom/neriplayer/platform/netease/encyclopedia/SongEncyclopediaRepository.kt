package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia

/** 歌曲百科查询结果。调用方必须处理全部四种分支，不能把缺失当成失败。 */
sealed interface SongEncyclopediaResult {
    /** 成功取到百科数据。 */
    data class Success(val data: SongEncyclopedia) : SongEncyclopediaResult

    /** 当前歌曲来源没有可用的百科数据源（本地文件、B 站、YouTube 等）。 */
    data object NotSupported : SongEncyclopediaResult

    /** 数据源可达，但这首歌确实没有百科内容，应展示「暂无相关资料」。 */
    data object NotAvailable : SongEncyclopediaResult

    /** 网络或解析失败，可重试。 */
    data class Failed(val cause: Throwable) : SongEncyclopediaResult
}

/**
 * 歌曲百科数据源。
 *
 * 实现必须：校验返回数据仍对应当前歌曲（[SongEncyclopedia.songKey]）、
 * 让 [kotlinx.coroutines.CancellationException] 向上传播、并对结果做内存缓存。
 */
interface SongEncyclopediaRepository {
    suspend fun getEncyclopedia(song: SongItem): SongEncyclopediaResult
}
