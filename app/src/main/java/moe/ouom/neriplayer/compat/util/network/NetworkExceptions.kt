package moe.ouom.neriplayer.util

import moe.ouom.neriplayer.util.network.isTransientHttp2StreamReset as isTransientHttp2StreamResetImpl

fun Throwable.isTransientHttp2StreamReset(): Boolean {
    return this.isTransientHttp2StreamResetImpl()
}
