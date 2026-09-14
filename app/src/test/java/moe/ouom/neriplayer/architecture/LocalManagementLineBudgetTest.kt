package moe.ouom.neriplayer.architecture

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalManagementLineBudgetTest {
    @Test
    fun `local management source and tests stay below 2000 physical lines`() {
        val projectRoot = LocalManagementLineBudget.findProjectRoot(
            File(System.getProperty("user.dir") ?: ".")
        )
        val report = LocalManagementLineBudget.verify(projectRoot)

        assertTrue(report.describe(), report.violations.isEmpty())
    }
}

internal data class LocalManagementFileScope(
    val recursiveDirectories: List<String>,
    val requiredFiles: List<String>,
    val uiFilePrefixes: List<UiFilePrefix>
)

internal data class UiFilePrefix(
    val directory: String,
    val prefix: String
)

internal data class LocalManagementLineBudgetReport(
    val inspectedFiles: List<String>,
    val violations: List<String>
) {
    fun describe(): String = buildString {
        append("本地管理文件必须少于 ")
        append(LocalManagementLineBudget.MAX_EXCLUSIVE)
        append(" 个物理行")
        if (violations.isNotEmpty()) {
            append("\n")
            append(violations.joinToString(separator = "\n"))
        }
    }
}

internal object LocalManagementLineBudget {
    const val MAX_EXCLUSIVE = 2_000

    private const val MAIN_PACKAGE_ROOT = "app/src/main/java/moe/ouom/neriplayer"
    private const val TEST_PACKAGE_ROOT = "app/src/test/java/moe/ouom/neriplayer"
    private const val ANDROID_TEST_PACKAGE_ROOT =
        "app/src/androidTest/java/moe/ouom/neriplayer"

    private val defaultScope = LocalManagementFileScope(
        recursiveDirectories = listOf(
            "$MAIN_PACKAGE_ROOT/core/download",
            "$MAIN_PACKAGE_ROOT/core/player/download",
            "$MAIN_PACKAGE_ROOT/data/local",
            "$TEST_PACKAGE_ROOT/core/download",
            "$TEST_PACKAGE_ROOT/core/player/download",
            "$TEST_PACKAGE_ROOT/data/local",
            "$TEST_PACKAGE_ROOT/architecture",
            "$ANDROID_TEST_PACKAGE_ROOT/core/download",
            "$ANDROID_TEST_PACKAGE_ROOT/core/player/download",
            "$ANDROID_TEST_PACKAGE_ROOT/data/local"
        ),
        requiredFiles = listOf(
            "$MAIN_PACKAGE_ROOT/core/download/GlobalDownloadManager.kt",
            "$MAIN_PACKAGE_ROOT/core/download/ManagedDownloadStorage.kt",
            "$MAIN_PACKAGE_ROOT/core/download/execution/DownloadExecutionRoomStore.kt",
            "$MAIN_PACKAGE_ROOT/core/download/execution/DownloadExecutionHost.kt",
            "$MAIN_PACKAGE_ROOT/core/player/download/AudioDownloadManager.kt",
            "$MAIN_PACKAGE_ROOT/data/local/media/LocalMediaSupport.kt",
            "$MAIN_PACKAGE_ROOT/data/local/audioimport/LocalAudioImportManager.kt",
            "$MAIN_PACKAGE_ROOT/data/local/playlist/LocalPlaylistRepository.kt",
            "$MAIN_PACKAGE_ROOT/ui/screen/tab/LibraryScreen.kt",
            "$MAIN_PACKAGE_ROOT/ui/screen/playlist/LocalPlaylistDetailScreen.kt",
            "$TEST_PACKAGE_ROOT/ui/screen/playlist/LocalPlaylistDetailMutableValueTest.kt"
        ),
        uiFilePrefixes = listOf(
            UiFilePrefix("$MAIN_PACKAGE_ROOT/ui/screen/tab", "LibraryScreen"),
            UiFilePrefix("$MAIN_PACKAGE_ROOT/ui/screen/playlist", "LocalPlaylistDetail"),
            UiFilePrefix("$TEST_PACKAGE_ROOT/ui/screen/tab", "LibraryScreen"),
            UiFilePrefix("$TEST_PACKAGE_ROOT/ui/screen/playlist", "LocalPlaylistDetail"),
            UiFilePrefix("$ANDROID_TEST_PACKAGE_ROOT/ui/screen/tab", "LibraryScreen"),
            UiFilePrefix(
                "$ANDROID_TEST_PACKAGE_ROOT/ui/screen/playlist",
                "LocalPlaylistDetail"
            )
        )
    )

    fun findProjectRoot(startDirectory: File): File {
        var current = startDirectory.absoluteFile
        while (true) {
            if (isProjectRoot(current)) return current.canonicalFile
            current = current.parentFile ?: break
        }
        error(
            "无法定位项目根目录：${startDirectory.absolutePath}，需要 settings.gradle.kts、" +
                "app/build.gradle.kts 和 ${defaultScope.requiredFiles.first()}"
        )
    }

    fun verify(
        projectRoot: File,
        scope: LocalManagementFileScope = defaultScope
    ): LocalManagementLineBudgetReport {
        val root = projectRoot.canonicalFile
        val violations = mutableListOf<String>()
        val candidates = linkedMapOf<String, File>()

        scope.requiredFiles.sorted().forEach { relativePath ->
            val file = File(root, relativePath)
            if (!file.isFile) {
                violations += "缺少必须保留的文件：$relativePath"
            } else {
                addCandidate(root, file, relativePath, candidates, violations)
            }
        }

        scope.recursiveDirectories.sorted().forEach { relativeDirectory ->
            val directory = File(root, relativeDirectory)
            if (!directory.exists()) return@forEach
            if (!directory.isDirectory) {
                violations += "受保护路径不是目录：$relativeDirectory"
                return@forEach
            }
            if (!isContainedRegularPath(root, directory, relativeDirectory, violations)) {
                return@forEach
            }
            directory.walkTopDown()
                .filter { file -> file.isFile && file.extension in SOURCE_EXTENSIONS }
                .sortedBy { file -> file.relativeTo(root).invariantSeparatorsPath }
                .forEach { file ->
                    addCandidate(
                        root,
                        file,
                        file.relativeTo(root).invariantSeparatorsPath,
                        candidates,
                        violations
                    )
                }
        }

        scope.uiFilePrefixes.sortedWith(compareBy(UiFilePrefix::directory, UiFilePrefix::prefix))
            .forEach { rule ->
                val directory = File(root, rule.directory)
                if (!directory.exists()) return@forEach
                if (!directory.isDirectory) {
                    violations += "受保护 UI 路径不是目录：${rule.directory}"
                    return@forEach
                }
                if (!isContainedRegularPath(root, directory, rule.directory, violations)) {
                    return@forEach
                }
                directory.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            file.extension in SOURCE_EXTENSIONS &&
                            file.name.startsWith(rule.prefix)
                    }
                    .sortedBy { file -> file.relativeTo(root).invariantSeparatorsPath }
                    .forEach { file ->
                        addCandidate(
                            root,
                            file,
                            file.relativeTo(root).invariantSeparatorsPath,
                            candidates,
                            violations
                        )
                    }
            }

        candidates.toSortedMap().forEach { (relativePath, file) ->
            val lineCount = try {
                countPhysicalLines(file)
            } catch (error: IOException) {
                violations += "无法读取 $relativePath：${error.message ?: error::class.java.simpleName}"
                return@forEach
            }
            if (lineCount >= MAX_EXCLUSIVE) {
                violations += "$relativePath: $lineCount 个物理行，必须少于 $MAX_EXCLUSIVE"
            }
        }

        return LocalManagementLineBudgetReport(
            inspectedFiles = candidates.keys.sorted(),
            violations = violations.distinct().sorted()
        )
    }

    fun countPhysicalLines(file: File): Int = file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        var count = 0
        while (reader.readLine() != null) {
            count += 1
        }
        count
    }

    fun isWithinBudget(lineCount: Int): Boolean = lineCount < MAX_EXCLUSIVE

    private fun isProjectRoot(candidate: File): Boolean =
        File(candidate, "settings.gradle.kts").isFile &&
            File(candidate, "app/build.gradle.kts").isFile &&
            File(candidate, defaultScope.requiredFiles.first()).isFile

    private fun addCandidate(
        root: File,
        file: File,
        relativePath: String,
        candidates: MutableMap<String, File>,
        violations: MutableList<String>
    ) {
        if (!isContainedRegularPath(root, file, relativePath, violations)) return
        candidates.putIfAbsent(relativePath, file)
    }

    private fun isContainedRegularPath(
        root: File,
        candidate: File,
        relativePath: String,
        violations: MutableList<String>
    ): Boolean {
        if (Files.isSymbolicLink(candidate.toPath())) {
            violations += "不允许通过符号链接扫描受保护文件：$relativePath"
            return false
        }
        val canonicalCandidate = try {
            candidate.canonicalFile
        } catch (error: IOException) {
            violations += "无法解析受保护路径 $relativePath：${error.message ?: error::class.java.simpleName}"
            return false
        }
        if (!canonicalCandidate.toPath().startsWith(root.toPath())) {
            violations += "受保护路径逃出项目根目录：$relativePath"
            return false
        }
        return true
    }

    private val SOURCE_EXTENSIONS = setOf("kt", "java")
}
