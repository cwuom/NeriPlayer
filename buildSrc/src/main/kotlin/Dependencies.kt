fun kotlinx(name: String, version: String) = "org.jetbrains.kotlinx:kotlinx-$name:$version"

fun ktor(target: String, name: String): String {
    return "io.ktor:ktor-$target-$name:${Versions.ktorVersion}"
}

fun grpc(name: String, version: String) = "io.grpc:grpc-$name:$version"

object Versions {
    const val roomVersion = "2.5.0"
    const val ktorVersion = "2.3.3"
}