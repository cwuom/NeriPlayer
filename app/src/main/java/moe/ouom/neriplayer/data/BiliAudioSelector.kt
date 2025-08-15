package moe.ouom.neriplayer.data

import kotlin.collections.firstOrNull

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
 * File: moe.ouom.neriplayer.data/BiliAudioSelector
 * Created: 2025/8/14
 */

/**
 * 统一的音质偏好 key：
 * - "dolby"    : 杜比全景声（如有）
 * - "hires"    : Hi-Res（如有）
 * - "lossless" : 无损（如有）
 * - "high"     : 约 192kbps
 * - "medium"   : 约 128kbps
 * - "low"      : 约 64kbps
 *
 * 注意：不硬编码 B 站的 dash 音频 ID，统一用标签/比特率做选择，避免因平台变动导致崩溃。
 */

data class BiliAudioStreamInfo(
    val id: Int?,           // 30250(杜比) / 30251(Hi-Res) / 30280(192k)
    val mimeType: String,   // audio/eac3, audio/flac, audio/mp4 等
    val bitrateKbps: Int,   // 估算 kbps
    val qualityTag: String?,// "dolby" / "hires" / null
    val url: String
)

enum class BiliQuality(val key: String, val minBitrateKbps: Int) {
    DOLBY("dolby",      0),     // 标签优先
    HIRES("hires",    1000),
    LOSSLESS("lossless", 500),
    HIGH("high",       180),
    MEDIUM("medium",   120),
    LOW("low",          60);

    companion object {
        private val order = listOf(DOLBY, HIRES, LOSSLESS, HIGH, MEDIUM, LOW)

        fun fromKey(key: String): BiliQuality =
            order.find { it.key == key } ?: HIGH

        /** 返回从当前到更低的一条降级链 */
        fun degradeChain(from: BiliQuality): List<BiliQuality> {
            val startIdx = order.indexOf(from).coerceAtLeast(0)
            return order.drop(startIdx)
        }
    }
}

/** 在可用音轨中，按偏好从高到低选择第一条满足条件的；不满足则自动降级 */
fun selectStreamByPreference(
    available: List<BiliAudioStreamInfo>,
    preferredKey: String
): BiliAudioStreamInfo? {
    if (available.isEmpty()) return null
    val pref = BiliQuality.fromKey(preferredKey)

    val sorted = available.sortedByDescending { it.bitrateKbps }

    when (pref) {
        BiliQuality.DOLBY ->
            sorted.firstOrNull { it.qualityTag == "dolby" }?.let { return it }
        BiliQuality.HIRES ->
            sorted.firstOrNull { it.qualityTag == "hires" }?.let { return it }
        else -> Unit
    }

    for (q in BiliQuality.degradeChain(pref)) {
        val hit = when (q) {
            BiliQuality.DOLBY   -> sorted.firstOrNull { it.qualityTag == "dolby" }
            BiliQuality.HIRES   -> sorted.firstOrNull { it.qualityTag == "hires" }
            BiliQuality.LOSSLESS-> sorted.firstOrNull { it.bitrateKbps >= q.minBitrateKbps }
            else                -> sorted.firstOrNull { it.bitrateKbps >= q.minBitrateKbps }
        }
        if (hit != null) return hit
    }


    return sorted.firstOrNull()
}