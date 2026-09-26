package moe.ouom.neriplayer.core.api

import okhttp3.RequestBody
import okio.BufferedSink

// 禁止 OkHttp 在重定向或服务端重试响应后重复提交没有幂等键的写入
internal fun RequestBody.nonReplayable(): RequestBody {
    val body = this
    return object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }
}
