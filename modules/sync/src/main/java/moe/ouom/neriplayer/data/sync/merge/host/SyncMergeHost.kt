package moe.ouom.neriplayer.data.sync.merge.host

import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist

/** 合并规则只读取系统歌单身份和展示文案，宿主负责解析语言与 Android 资源 */
interface SyncMergeHost {
    val favoritesPlaylistId: Long
    val mergeSuccessMessage: String
    val initialUploadMessage: String
    fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist?
    fun localRenameMessage(name: String): String
    fun remoteRenameMessage(name: String): String
}
