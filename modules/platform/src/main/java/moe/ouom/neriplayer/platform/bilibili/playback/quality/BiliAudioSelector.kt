package moe.ouom.neriplayer.platform.bilibili.playback.quality

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.platform.bilibili/BiliAudioSelector
 * Created: 2025/8/14
 */

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliQuality
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo

private fun regularQualityUpperBoundExclusive(quality: BiliQuality): Int = when (quality) {
    BiliQuality.LOSSLESS -> BiliQuality.HIRES.minBitrateKbps
    BiliQuality.HIGH -> BiliQuality.LOSSLESS.minBitrateKbps
    BiliQuality.MEDIUM -> BiliQuality.HIGH.minBitrateKbps
    BiliQuality.LOW -> BiliQuality.MEDIUM.minBitrateKbps
    else -> Int.MAX_VALUE
}

private fun BiliAudioStreamInfo.normalizedQualityTag(): String? {
    return qualityTag
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
}

private fun matchesRegularQuality(
    stream: BiliAudioStreamInfo,
    quality: BiliQuality
): Boolean {
    if (stream.normalizedQualityTag() != null) return false
    val upperBoundExclusive = regularQualityUpperBoundExclusive(quality)
    return stream.bitrateKbps >= quality.minBitrateKbps &&
        stream.bitrateKbps < upperBoundExclusive
}

private fun isLosslessLikeStream(stream: BiliAudioStreamInfo): Boolean {
    val qualityTag = stream.normalizedQualityTag()
    if (qualityTag == "lossless" || qualityTag == "hires") return true
    val mimeType = stream.mimeType
        .substringBefore(';')
        .trim()
        .lowercase()
    return mimeType == "audio/flac" || mimeType == "audio/x-flac"
}

/** 在可用音轨中, 按偏好从高到低选择第一条满足条件的; 不满足则自动降级 */
fun selectStreamByPreference(
    available: List<BiliAudioStreamInfo>,
    preferredKey: String
): BiliAudioStreamInfo? {
    if (available.isEmpty()) return null
    val pref = BiliQuality.fromKey(preferredKey)

    val regularSorted = available
        .filter { it.normalizedQualityTag() == null }
        .sortedByDescending { it.bitrateKbps }
    val taggedSorted = available
        .filter { it.normalizedQualityTag() != null }
        .sortedByDescending { it.bitrateKbps }
    val sorted = (regularSorted + taggedSorted).distinctBy { it.url }

    when (pref) {
        BiliQuality.DOLBY ->
            sorted.firstOrNull { it.normalizedQualityTag() == "dolby" }?.let { return it }
        BiliQuality.HIRES ->
            sorted.firstOrNull { it.normalizedQualityTag() == "hires" }?.let { return it }
        BiliQuality.LOSSLESS ->
            sorted.firstOrNull(::isLosslessLikeStream)?.let { return it }
        else -> Unit
    }

    for (q in BiliQuality.degradeChain(pref)) {
        val hit = when (q) {
            BiliQuality.DOLBY   -> sorted.firstOrNull { it.normalizedQualityTag() == "dolby" }
            BiliQuality.HIRES   -> sorted.firstOrNull { it.normalizedQualityTag() == "hires" }
            BiliQuality.LOSSLESS ->
                sorted.firstOrNull(::isLosslessLikeStream)
                    ?: regularSorted.firstOrNull { matchesRegularQuality(it, q) }
            else -> regularSorted.firstOrNull { matchesRegularQuality(it, q) }
        }
        if (hit != null) return hit
    }

    return sorted.firstOrNull()
}

/**
 * 音轨跨视频比较用的音质档位，越大越好：
 * 3 = 无损 / 高解析（FLAC），2 = 杜比全景声，1 = 高码率（≥180kbps），0 = 其余。
 *
 * 换源时不同视频能提供的音轨差别很大，先比档位再比码率，才能挑出真正更清晰的那个。
 */
fun biliStreamQualityTier(stream: BiliAudioStreamInfo): Int {
    if (isLosslessLikeStream(stream)) return 3
    val qualityTag = stream.normalizedQualityTag()
    if (qualityTag == "dolby") return 2
    return if (stream.bitrateKbps >= BiliQuality.HIGH.minBitrateKbps) 1 else 0
}
