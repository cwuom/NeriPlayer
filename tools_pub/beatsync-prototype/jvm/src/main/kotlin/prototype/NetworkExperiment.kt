package prototype

import kotlinx.serialization.json.*
import okhttp3.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Received(val text:String,val t3:Double)
class ProbeClient(private val client:OkHttpClient,port:Int) : AutoCloseable {
    private val opened=CompletableFuture<Unit>()
    private val closed=CompletableFuture<Unit>()
    private val received=LinkedBlockingQueue<Received>()
    @Volatile private var failure:Throwable?=null
    private val socket=client.newWebSocket(Request.Builder().url("http://127.0.0.1:$port/ws").build(),object:WebSocketListener(){
        override fun onOpen(webSocket:WebSocket,response:Response) { opened.complete(Unit) }
        override fun onMessage(webSocket:WebSocket,text:String) { val t3=nowMs(); received.put(Received(text,t3)) }
        override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,reason) }
        override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { closed.complete(Unit) }
        override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { failure=t;opened.completeExceptionally(t);closed.completeExceptionally(t) }
    })
    init { opened.get(10,TimeUnit.SECONDS) }
    fun send(text:String) { failure?.let { throw it }; check(socket.send(text)) { "Send not accepted" } }
    fun receive():Received { failure?.let { throw it }; return received.poll(5,TimeUnit.SECONDS) ?: error("Receive timeout: $failure") }
    fun request(text:String):JsonObject { send(text);return Json.parseToJsonElement(receive().text).jsonObject }
    fun hello() { check(request("""{"type":"HELLO","protocolVersion":1}""")["type"]?.jsonPrimitive?.content=="HELLO_ACK") }
    fun probe(group:Int,index:Int) {
        val t0=nowMs()
        send(buildJsonObject { put("type","NTP_REQUEST");put("t0",t0);put("probeGroupId",group);put("probeGroupIndex",index) }.toString())
    }
    override fun close() {
        socket.close(1000,"prototype done")
        try { closed.get(5,TimeUnit.SECONDS) } finally { socket.cancel() }
    }
}
data class Sample(val t0:Double,val t1:Double,val t2:Double,val t3:Double) {
    val rtt get()=(t3-t0)-(t2-t1)
    val offset get()=((t1-t0)+(t2-t3))/2
    val processing get()=t2-t1
    fun json()=buildJsonObject { put("t0",t0);put("t1",t1);put("t2",t2);put("t3",t3);put("rttMs",rtt);put("offsetMs",offset) }
}
private fun Received.sample(group:Int,index:Int):Sample {
    val obj=Json.parseToJsonElement(text).jsonObject
    check(obj["type"]?.jsonPrimitive?.content=="NTP_RESPONSE") { text }
    check(obj["probeGroupId"]?.jsonPrimitive?.int==group && obj["probeGroupIndex"]?.jsonPrimitive?.int==index)
    return Sample(obj.getValue("t0").jsonPrimitive.double,obj.getValue("t1").jsonPrimitive.double,obj.getValue("t2").jsonPrimitive.double,t3)
}
fun networkExperiment() {
    val client=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).build()
    val reports=mutableListOf<JsonObject>()
    try {
        repeat(4) { round ->
            val factories:List<()->ProbeServer> = if(round%2==0) listOf({JavaWsProbeServer()},{KtorProbeServer()}) else listOf({KtorProbeServer()},{JavaWsProbeServer()})
            factories.forEach { factory ->
                factory().use { server ->
                    val start=nowMs();val port=server.start();val startupMs=nowMs()-start
                    val samples=mutableListOf<Sample>();val pairRecords=mutableListOf<JsonObject>()
                    var pure=0
                    // Independent second peer exercises simultaneous connections and control traffic.
                    ProbeClient(client,port).use { background ->
                        background.hello()
                        ProbeClient(client,port).use { ws ->
                            check(ws.request("""{"type":"NTP_REQUEST","t0":0,"probeGroupId":0,"probeGroupIndex":0}""")["code"]?.jsonPrimitive?.content=="NOT_READY")
                            check(ws.request("""{"type":"HELLO","protocolVersion":2}""")["code"]?.jsonPrimitive?.content=="VERSION")
                            ws.hello()
                            check(ws.request("{broken")["code"]?.jsonPrimitive?.content=="MALFORMED")
                            check(ws.request("""{"type":"NTP_REQUEST","t0":0,"probeGroupId":0,"probeGroupIndex":4}""")["code"]?.jsonPrimitive?.content=="INVALID_PROBE")
                            check(ws.request("""{"type":"NTP_REQUEST","probeGroupId":0,"probeGroupIndex":0}""")["code"]?.jsonPrimitive?.content=="INVALID_PROBE")
                            check(ws.request("""{"type":"UNSUPPORTED"}""")["code"]?.jsonPrimitive?.content=="UNKNOWN")
                            repeat(70) { group ->
                                ws.probe(group,0)
                                Thread.sleep(25)
                                ws.probe(group,1)
                                background.send("""{"type":"PING"}""")
                                val a=ws.receive().sample(group,0);val b=ws.receive().sample(group,1)
                                check(Json.parseToJsonElement(background.receive().text).jsonObject["type"]?.jsonPrimitive?.content=="PONG")
                                if(group>=10) {
                                    val drift=abs((b.t1-a.t1)-(b.t0-a.t0));val accepted=drift<=5
                                    if(accepted) pure++
                                    samples+=a;samples+=b
                                    pairRecords+=buildJsonObject {
                                        put("group",group);put("gapDriftMs",drift);put("pure",accepted)
                                        put("first",a.json());put("second",b.json())
                                    }
                                }
                                Thread.sleep(25)
                            }
                        }
                        // Real close/open, fresh handshake; stale session state is not reused.
                        ProbeClient(client,port).use { reconnected -> reconnected.hello();check(reconnected.request("""{"type":"PING"}""")["type"]?.jsonPrimitive?.content=="PONG") }
                    }
                    val report=buildJsonObject {
                        put("server",server.name);put("round",round);put("startupMs",startupMs)
                        put("measuredPairs",60);put("warmupPairs",10);put("purePairs",pure)
                        put("rttMs",percentiles(samples.map{it.rtt}));put("processingMs",percentiles(samples.map{it.processing}))
                        put("absoluteOffsetMs",percentiles(samples.map{abs(it.offset)}));put("pairs",JsonArray(pairRecords))
                        put("protocolChecks","pre-hello, version, malformed, bad-index, missing-t0, unknown, 2 peers, close/reconnect")
                    }
                    reports+=report
                    println("${server.name} round=$round pure=$pure/60 rtt=${report["rttMs"]}")
                }
            }
        }
    } finally { client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    val report=buildJsonObject {
        put("platform",System.getProperty("os.name"));put("java",System.getProperty("java.version"))
        put("versions","Java-WebSocket 1.6.0; Ktor CIO 3.6.0; OkHttp 5.4.0")
        put("scope","Windows JVM loopback, 2 concurrent peers, 4 alternating rounds per implementation")
        put("limitations","No Wi-Fi, Android, audio, background, packet loss or production room state machine measured. Receive timestamp boundaries differ by library. Not a cold-start benchmark.")
        put("rounds",JsonArray(reports))
    }
    dataFile("results/websocket-loopback.json").writeText(pretty.encodeToString(JsonObject.serializer(),report))
}
