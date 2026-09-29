package moe.ouom.neriplayer.data.platform.bili.skip.storage

import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipSnapshot

import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipRule

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
