package moe.ouom.neriplayer.platform.bilibili.skip.storage

import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipSnapshot

import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipRule

interface BiliVideoSkipStore {
    suspend fun isPrimary(): Boolean
    suspend fun readIfPrimary(): BiliVideoSkipSnapshot?
    suspend fun replaceAll(
        rules: List<BiliVideoSkipRule>,
        drafts: List<BiliVideoSkipDraft>,
        now: Long = System.currentTimeMillis()
    )
    suspend fun replaceRules(
        rules: List<BiliVideoSkipRule>,
        now: Long = System.currentTimeMillis()
    )
    suspend fun replaceDrafts(
        drafts: List<BiliVideoSkipDraft>,
        now: Long = System.currentTimeMillis()
    )
}
