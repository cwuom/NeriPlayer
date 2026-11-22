package prototype

import kotlinx.serialization.json.*
import java.lang.reflect.InvocationTargetException

// Uses Zipline's actual embedded QuickJS host API, without the Kotlin/JS compiler plugin.
fun quickJsExperiment() {
    var engine:Any?=null
    val outcome:JsonObject
    try {
        val type=Class.forName("app.cash.zipline.QuickJs")
        val companion=type.getField("Companion").get(null)
        engine=companion.javaClass.getMethod("create").invoke(companion)
        val evaluate=type.methods.first { it.name=="evaluate" && it.parameterTypes.toList()==listOf(String::class.java,String::class.java) }
        fun eval(code:String):String = evaluate.invoke(engine,code,"prototype.js")?.toString() ?: "undefined"
        eval("var console = {log: function(){}};")
        eval(dataFile("js/generated/core.js").readText())
        val input=dataFile("js/generated/scenarios.json").readText()
        val result=eval("BeatSyncPrototype.runJson(${JsonPrimitive(input)})")
        val expected=Json.parseToJsonElement(dataFile("js/generated/oracle.json").readText())
        compareJson(expected,Json.parseToJsonElement(result))
        val small = """[{"op":"wait","now":10000.125,"target":11000.375,"offset":100.25}]"""
        val invocation = "BeatSyncPrototype.runJson(${JsonPrimitive(small)})"
        repeat(1000) { check(eval(invocation)=="[900]") }
        val timing=(0 until 3000).map {
            val start=System.nanoTime()
            check(eval(invocation)=="[900]")
            (System.nanoTime()-start)/1e6
        }
        outcome=buildJsonObject {
            put("status","passed");put("engine","Zipline QuickJS 1.28.0");put("cases",expected.jsonArray.size)
            put("mismatches",0);put("absoluteTolerance",1e-6);put("bridgeRoundTripMs",percentiles(timing))
            put("warmupCalls",1000);put("measuredCalls",3000)
            put("platform",System.getProperty("os.name"));put("architecture",System.getProperty("os.arch"))
            put("boundary","JVM evaluate(String) -> native QuickJS parse invocation+JS JSON parse+original function+stringify -> JVM String")
            put("limitations","No full Zipline Kotlin/JS bridge, compiler plugin or audio deadlines tested. Different invocation path from Rhino Function.call; do not rank engines by these times.")
        }
    } catch(t:Throwable) {
        var cause=t
        while(cause is InvocationTargetException || cause is ExceptionInInitializerError) { cause=cause.cause ?: break }
        val failed=buildJsonObject {
            put("status","failed");put("engine","Zipline QuickJS 1.28.0")
            put("exception",cause.javaClass.name);put("detail",cause.message ?: "no message")
            put("platform",System.getProperty("os.name"));put("architecture",System.getProperty("os.arch"))
        }
        dataFile("results/zipline-quickjs.json").writeText(pretty.encodeToString(JsonObject.serializer(),failed))
        throw cause
    } finally { (engine as? AutoCloseable)?.close() }
    dataFile("results/zipline-quickjs.json").writeText(pretty.encodeToString(JsonObject.serializer(),outcome))
    println(outcome)
}
fun main() = quickJsExperiment()
