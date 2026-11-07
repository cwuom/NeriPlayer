package prototype.smoke

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import prototype.*

class MainActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val status=TextView(this).apply { text="Runs bundled BeatSync code and loopback WS checks. No LAN listener or audio playback. Results stay in app files/results." }
        val button=Button(this).apply { text="Run validation" }
        setContentView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24);addView(status);addView(button) })
        button.setOnClickListener {
            button.isEnabled=false
            status.text="Running; keep this test app in the foreground."
            Thread {
                val outcome=runCatching {
                    experimentDirectory=filesDir
                    File(filesDir,"results").mkdirs()
                    File(filesDir,"js/generated").mkdirs()
                    for(name in listOf("core.js","core-es5.js","scenarios.json","oracle.json")) {
                        assets.open(name).use { input -> File(filesDir,"js/generated/$name").outputStream().use {input.copyTo(it)} }
                    }
                    jsExperiment()
                    quickJsExperiment()
                    networkExperiment()
                    "Finished. Inspect results JSON; QuickJS reports its own status."
                }.getOrElse { error ->
                    File(filesDir,"results/failure.txt").apply {parentFile?.mkdirs();writeText(error.stackTraceToString())}
                    "Failed: ${error.javaClass.simpleName}: ${error.message}"
                }
                runOnUiThread {status.text=outcome;button.isEnabled=true}
            }.start()
        }
    }
}
