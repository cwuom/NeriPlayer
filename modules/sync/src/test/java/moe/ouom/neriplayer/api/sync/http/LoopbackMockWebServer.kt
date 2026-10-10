package moe.ouom.neriplayer.api.sync.http

import mockwebserver3.MockWebServer
import java.net.InetAddress

// localhost 会同时解析出 ::1 和 127.0.0.1，OkHttp 先连 ::1；
// 服务只监听 127.0.0.1 时，别的进程占着 ::1 同端口就会截走测试请求
private val loopbackV4: InetAddress = InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1))

internal fun MockWebServer.startOnLoopback() = start(loopbackV4, 0)
