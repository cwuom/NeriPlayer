package prototype
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.ceil
fun main(args:Array<String>) {
    dataFile("results").mkdirs()
    when(args.singleOrNull() ?: "all") {
        "js" -> jsExperiment()
        "network" -> networkExperiment()
        "all" -> { jsExperiment();networkExperiment() }
        else -> error("Expected js, network or all")
    }
}
