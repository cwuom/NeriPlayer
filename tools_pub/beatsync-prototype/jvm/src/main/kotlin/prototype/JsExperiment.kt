package prototype

import kotlinx.serialization.json.*
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.io.File
import kotlin.math.abs

fun jsExperiment() {
    val cx = Context.enter()
    try {
        cx.languageVersion = Context.VERSION_ES6
        cx.optimizationLevel = -1 // Interpret; avoid relying on JVM bytecode generation unavailable on Android.
        val scope = cx.initStandardObjects()
        cx.evaluateString(scope, "var globalThis = this; var console = {log: function(){}};", "host", 1, null)
        val started = System.nanoTime()
        cx.evaluateString(scope, dataFile("js/generated/core-es5.js").readText(), "beatsync-original", 1, null)
        val loadMs = (System.nanoTime() - started) / 1e6
        val api = ScriptableObject.getProperty(scope, "BeatSyncPrototype") as org.mozilla.javascript.Scriptable
        val run = ScriptableObject.getProperty(api, "runJson") as Function
        val input = dataFile("js/generated/scenarios.json").readText()
        val expected = Json.parseToJsonElement(dataFile("js/generated/oracle.json").readText())
        val result = Context.toString(run.call(cx, scope, api, arrayOf(input)))
        compareJson(expected, Json.parseToJsonElement(result))
        val small = """[{"op":"wait","now":10000.125,"target":11000.375,"offset":100.25}]"""
        repeat(1000) { run.call(cx, scope, api, arrayOf(small)) }
        val timing = (0 until 3000).map {
            val t = System.nanoTime()
            check(Context.toString(run.call(cx, scope, api, arrayOf(small))) == "[900]")
            (System.nanoTime() - t) / 1e6
        }
        val report = buildJsonObject {
            put("engine", "Rhino 1.8.0 (interpreter)"); put("platform", System.getProperty("os.name"))
            put("java", System.getProperty("java.version")); put("cases", expected.jsonArray.size)
            put("mismatches", 0); put("absoluteTolerance", 1e-6); put("bundleLoadMs", loadMs)
            put("bridgeRoundTripMs", percentiles(timing)); put("warmupCalls",1000); put("measuredCalls",timing.size)
            put("boundary", "JVM String -> JS parse+original wait function+stringify -> JVM String")
            put("limitations", "Desktop JVM only; no Android/R8/audio deadline validation; measurements include no network")
        }
        dataFile("results/rhino.json").writeText(pretty.encodeToString(JsonObject.serializer(), report))
        println("Rhino: ${expected.jsonArray.size} scenarios match Node; ${report["bridgeRoundTripMs"]}")
    } finally { Context.exit() }
}
