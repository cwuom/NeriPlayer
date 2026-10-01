package moe.ouom.neriplayer.core.player.testing

import java.io.File

internal fun locatePlayerProjectFile(path: String): File {
    var directory = File(System.getProperty("user.dir") ?: ".")
    repeat(8) {
        val direct = File(directory, path)
        if (direct.isFile) return direct
        if (File(directory, "settings.gradle.kts").isFile) {
            val packagePath = path.removePrefix("app/src/main/java/")
            val candidates = File(directory, "modules").listFiles().orEmpty().flatMap { layer ->
                layer.listFiles().orEmpty().map { File(it, "src/main/java/$packagePath") }
            }.filter(File::isFile)
            check(candidates.size == 1) { "Expected one source for $path, found $candidates" }
            return candidates.single()
        }
        directory = directory.parentFile ?: return@repeat
    }
    error("Project source file not found: $path")
}
