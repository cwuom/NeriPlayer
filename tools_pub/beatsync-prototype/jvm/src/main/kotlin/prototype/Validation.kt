package prototype
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
var experimentDirectory=File(".")
fun dataFile(path:String)=File(experimentDirectory,path)
val pretty=Json { prettyPrint=true }
fun percentiles(values:List<Double>):JsonObject {
    val sorted=values.sorted()
    fun p(q:Double)=sorted[(ceil(q*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)]
    return buildJsonObject { put("p50",p(.5));put("p95",p(.95));put("p99",p(.99));put("max",sorted.last()) }
}

internal fun compareJson(expected: JsonElement, actual: JsonElement, path: String = "root") {
    when {
        expected is JsonObject && actual is JsonObject -> {
            check(expected.keys == actual.keys) { "Keys differ at $path" }
            expected.forEach { (key, value) -> compareJson(value, actual.getValue(key), "$path.$key") }
        }
        expected is JsonArray && actual is JsonArray -> {
            check(expected.size == actual.size) { "Array sizes differ at $path" }
            expected.indices.forEach { compareJson(expected[it], actual[it], "$path[$it]") }
        }
        expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString && expected.doubleOrNull != null && actual.doubleOrNull != null -> {
            check(abs(expected.double - actual.double) <= 1e-6) { "Numbers differ at $path: $expected / $actual" }
        }
        else -> check(expected == actual) { "Values differ at $path: $expected / $actual" }
    }
}


