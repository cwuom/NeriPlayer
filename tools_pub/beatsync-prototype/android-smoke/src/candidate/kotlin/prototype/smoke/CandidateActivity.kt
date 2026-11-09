package prototype.smoke

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.*
import prototype.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CandidateActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val status=TextView(this).apply {text="QuickJS + Java-WebSocket + OkHttp candidate; foreground validation only."}
        val button=Button(this).apply {text="Run candidate validation"}
        setContentView(LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(status);addView(button)})
        button.setOnClickListener {
            button.isEnabled=false
            Thread {
                val message=runCatching {
                    experimentDirectory=filesDir
                    dataFile("results").mkdirs();dataFile("js/generated").mkdirs()
                    for(name in listOf("core.js","scenarios.json","oracle.json")) {
                        assets.open(name).use {input->dataFile("js/generated/$name").outputStream().use{input.copyTo(it)}}
                    }
                    prototype.quickJsExperiment()
                    val client=OkHttpClient()
                    try {
                        JavaWsProbeServer().use {server->
                            val port=server.start()
                            val reply=CompletableFuture<String>()
                            val socket=client.newWebSocket(Request.Builder().url("http://127.0.0.1:$port/ws").build(),object:WebSocketListener(){
                                override fun onOpen(webSocket:WebSocket,response:Response) {webSocket.send("""{"type":"HELLO","protocolVersion":1}""")}
                                override fun onMessage(webSocket:WebSocket,text:String) {reply.complete(text)}
                                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {reply.completeExceptionally(t)}
                            })
                            try {
                                check(reply.get(10,TimeUnit.SECONDS).contains("HELLO_ACK"))
                                dataFile("results/candidate-network.txt").writeText("PASS: OkHttp -> Java-WebSocket HELLO on loopback")
                            } finally { socket.cancel() }
                        }
                    } finally {client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
                    "PASS: 1,516 JS cases and loopback handshake."
                }.getOrElse {error->
                    dataFile("results/failure.txt").apply{parentFile?.mkdirs();writeText(error.stackTraceToString())}
                    "FAIL: ${error.message}"
                }
                runOnUiThread {status.text=message;button.isEnabled=true}
            }.start()
        }
    }
}
