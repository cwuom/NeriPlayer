package prototype
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
class KtorProbeServer : ProbeServer {
    override val name = "ktor-cio"
    private val server = embeddedServer(CIO,host="127.0.0.1",port=0) {
        install(WebSockets) { maxFrameSize=65536 }
        routing {
            webSocket("/ws") {
                val handler=ProbeHandler()
                for (frame in incoming) {
                    val t1=nowMs()
                    if (frame is Frame.Text) send(Frame.Text(handler.respond(frame.readText(),t1)))
                }
            }
        }
    }
    override fun start():Int {
        server.start(wait=false)
        return runBlocking { server.engine.resolvedConnectors().single().port }
    }
    override fun close() { server.stop(100,2000) }
}

