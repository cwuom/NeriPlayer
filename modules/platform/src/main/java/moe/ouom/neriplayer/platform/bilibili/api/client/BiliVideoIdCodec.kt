package moe.ouom.neriplayer.platform.bilibili.api.client

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
 * File: moe.ouom.neriplayer.platform.bilibili.api.client/BiliVideoIdCodec
 * Updated: 2026/10/15
 */

/**
 * B 站 av 号与 BV 号互转。
 *
 * 移动端分区榜接口（`app.bilibili.com/x/v2/rank/region`）只返回 `param`（即 avid），
 * 而现有播放链路要求条目携带 bvid，因此需要在本地完成换算，避免为每个条目额外发一次请求。
 *
 * 算法与官方前端一致：把 `(MAX_AID | aid) ^ XOR_CODE` 按 58 进制依次填入 BV 号的最低 6 位，
 * 最后交换第 3↔9、4↔7 位。字母表去掉了易混淆的 `I`/`l`/`O`/`0`。
 */
internal object BiliVideoIdCodec {

    /** BV 号字母表（58 个字符，顺序由官方实现固定） */
    private const val ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

    private const val XOR_CODE = 23442827791579L

    /** `1L shl 51`，与官方 `MAX_AID` 一致 */
    private const val MAX_AID = 1L shl 51

    private const val BASE = 58

    /** 交换位：官方实现对这两组下标做了对调 */
    private const val SWAP_A = 3
    private const val SWAP_B = 9
    private const val SWAP_C = 4
    private const val SWAP_D = 7

    private const val BV_LENGTH = 12

    /** 未填充时的占位模板，前缀 `BV1` 固定 */
    private const val BV_TEMPLATE = "BV1000000000"

    /**
     * 把 avid 换算成 bvid，非法输入返回空串。
     */
    fun aidToBvid(aid: Long): String {
        if (aid <= 0L) return ""
        val chars = BV_TEMPLATE.toCharArray()
        if (chars.size != BV_LENGTH) return ""

        var remainder = (MAX_AID or aid) xor XOR_CODE
        var index = BV_LENGTH - 1
        // 数据位是下标 3..11 共 9 位，不能覆盖前缀 BV1
        while (remainder > 0L && index >= SWAP_A) {
            chars[index] = ALPHABET[(remainder % BASE).toInt()]
            remainder /= BASE
            index -= 1
        }

        chars[SWAP_A] = chars[SWAP_B].also { chars[SWAP_B] = chars[SWAP_A] }
        chars[SWAP_C] = chars[SWAP_D].also { chars[SWAP_D] = chars[SWAP_C] }
        return String(chars)
    }

    /**
     * 把 bvid 换算成 avid，非法输入返回 0。
     *
     * 目前只用于本地校验与兜底，正常播放链路不需要。
     */
    fun bvidToAid(bvid: String): Long {
        val trimmed = bvid.trim()
        if (trimmed.length != BV_LENGTH || !trimmed.startsWith("BV")) return 0L
        val chars = trimmed.toCharArray()
        chars[SWAP_A] = chars[SWAP_B].also { chars[SWAP_B] = chars[SWAP_A] }
        chars[SWAP_C] = chars[SWAP_D].also { chars[SWAP_D] = chars[SWAP_C] }

        var result = 0L
        // 前 3 位固定为 BV1，数据位是下标 3..11 共 9 位
        for (i in SWAP_A until BV_LENGTH) {
            val value = ALPHABET.indexOf(chars[i])
            if (value < 0) return 0L
            result = result * BASE + value
        }
        // 先异或还原 (MAX_AID | aid)，再用掩码去掉最高位得到 aid
        return (result xor XOR_CODE) and (MAX_AID - 1L)
    }
}
