package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException

/**
 * 多页接口的聚合结果
 *
 * [missingPages] 是请求失败或因风控没有再请求的分页数, 大于 0 时 [items] 只是部分内容
 */
data class BiliPagedItems<T>(
    val items: List<T>,
    val missingPages: Int = 0
) {
    val isComplete: Boolean
        get() = missingPages == 0
}

internal class BiliHttpStatusException(
    val statusCode: Int,
    body: String
) : IOException("HTTP $statusCode: $body")
