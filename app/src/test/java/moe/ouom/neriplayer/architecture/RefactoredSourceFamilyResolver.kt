package moe.ouom.neriplayer.architecture

import java.io.File

/**
 * 源码契约测试读取拆分后的同职责文件族，避免要求 facade 保留已迁出的实现
 */
internal object RefactoredSourceFamilyResolver {
    private val familyPrefixByRootName = mapOf(
        "GlobalDownloadManager.kt" to "GlobalDownloadManager",
        "ManagedDownloadStorage.kt" to "ManagedDownloadStorage",
        "DownloadExecutionHost.kt" to "DownloadExecutionHost",
        "DownloadExecutionRoomStore.kt" to "DownloadExecutionRoomStore",
        "AudioDownloadManager.kt" to "AudioDownloadManager",
        "LocalMediaSupport.kt" to "LocalMediaSupport",
        "LocalAudioImportManager.kt" to "LocalAudioImport",
        "LocalPlaylistRepository.kt" to "LocalPlaylistRepository"
    )

    private val implementationDirectoriesByRootName = mapOf(
        "GlobalDownloadManager.kt" to listOf("manager"),
        "ManagedDownloadStorage.kt" to listOf("storage/facade", "storage/operation")
    )

    fun resolve(candidate: File): File {
        val prefix = familyPrefixByRootName[candidate.name] ?: return candidate
        val parent = candidate.parentFile ?: return candidate
        val familyFileCandidates = sequence {
            yieldAll(parent.listFiles()?.asSequence().orEmpty())
            implementationDirectoriesByRootName[candidate.name]
                .orEmpty()
                .map { relativePath -> File(parent, relativePath) }
                .filter(File::isDirectory)
                .forEach { directory ->
                    yieldAll(directory.walkTopDown())
                }
        }
        val familyFiles = familyFileCandidates
            .filter { file ->
                file.isFile &&
                    file.extension == "kt" &&
                    (file.name == candidate.name || file.name.startsWith(prefix))
            }
            .distinctBy { file -> file.absolutePath }
            .sortedWith(
                compareBy<File> { file -> file.name != candidate.name }
                    .thenBy { file -> file.name }
            )
            .toList()
        if (familyFiles.size <= 1) return candidate

        return File.createTempFile("refactored-source-family-", ".kt").apply {
            deleteOnExit()
            writeText(familyFiles.joinToString(separator = "\n\n") { file -> file.readText() })
        }
    }

    fun functionBody(source: String, methodName: String): String {
        val signatureStart = listOf("${methodName}Impl", methodName)
            .firstNotNullOfOrNull { candidateName ->
                extensionFunctionSignature(candidateName).find(source)?.range?.first
                    ?: unqualifiedFunctionSignature(candidateName).find(source)?.range?.first
            }
            ?: error("method not found in refactored source family: $methodName")
        val bodyStart = findFunctionBodyStart(source, signatureStart, methodName)
        return source.substring(bodyStart, findBodyEnd(source, bodyStart, methodName))
    }

    fun bodyEnd(source: String, bodyStart: Int, description: String): Int =
        findBodyEnd(source, bodyStart, description)

    fun functionBodyFromSignature(source: String, signature: String): String {
        val signatureStart = source.indexOf(signature)
        require(signatureStart >= 0) { "method signature not found: $signature" }
        val bodyStart = findFunctionBodyStart(source, signatureStart, signature)
        return source.substring(bodyStart, findBodyEnd(source, bodyStart, signature))
    }

    private fun extensionFunctionSignature(name: String): Regex = Regex(
        pattern = """(?m)^\s*(?:private|internal|public)?\s*(?:override\s+)?(?:inline\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s+)?[A-Za-z_][A-Za-z0-9_]*\.${Regex.escape(name)}\b"""
    )

    private fun unqualifiedFunctionSignature(name: String): Regex = Regex(
        pattern = """(?m)^\s*(?:private|internal|public)?\s*(?:override\s+)?(?:inline\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s+)?${Regex.escape(name)}\b"""
    )

    private fun findFunctionBodyStart(source: String, signatureStart: Int, methodName: String): Int {
        var parenthesisDepth = 0
        var index = signatureStart
        var state = SourceLexState.NORMAL
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                SourceLexState.NORMAL -> when {
                    current == '/' && next == '/' -> {
                        state = SourceLexState.LINE_COMMENT
                        index++
                    }
                    current == '/' && next == '*' -> {
                        state = SourceLexState.BLOCK_COMMENT
                        index++
                    }
                    current == '"' && source.startsWith("\"\"\"", index) -> {
                        state = SourceLexState.TRIPLE_QUOTE
                        index += 2
                    }
                    current == '"' -> state = SourceLexState.DOUBLE_QUOTE
                    current == '\'' -> state = SourceLexState.CHAR_QUOTE
                    current == '(' -> parenthesisDepth++
                    current == ')' -> parenthesisDepth--
                    current == '{' && parenthesisDepth == 0 -> return index
                }
                SourceLexState.LINE_COMMENT -> if (current == '\n') {
                    state = SourceLexState.NORMAL
                }
                SourceLexState.BLOCK_COMMENT -> if (current == '*' && next == '/') {
                    state = SourceLexState.NORMAL
                    index++
                }
                SourceLexState.DOUBLE_QUOTE,
                SourceLexState.CHAR_QUOTE -> if (current == '\\') {
                    index++
                } else if (
                    (state == SourceLexState.DOUBLE_QUOTE && current == '"') ||
                        (state == SourceLexState.CHAR_QUOTE && current == '\'')
                ) {
                    state = SourceLexState.NORMAL
                }
                SourceLexState.TRIPLE_QUOTE -> if (
                    current == '"' && source.startsWith("\"\"\"", index)
                ) {
                    state = SourceLexState.NORMAL
                    index += 2
                }
            }
            index++
        }
        error("method body not found: $methodName")
    }

    private fun findBodyEnd(source: String, bodyStart: Int, methodName: String): Int {
        var depth = 0
        var index = bodyStart
        var state = SourceLexState.NORMAL
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                SourceLexState.NORMAL -> when {
                    current == '/' && next == '/' -> {
                        state = SourceLexState.LINE_COMMENT
                        index++
                    }
                    current == '/' && next == '*' -> {
                        state = SourceLexState.BLOCK_COMMENT
                        index++
                    }
                    current == '"' && source.startsWith("\"\"\"", index) -> {
                        state = SourceLexState.TRIPLE_QUOTE
                        index += 2
                    }
                    current == '"' -> state = SourceLexState.DOUBLE_QUOTE
                    current == '\'' -> state = SourceLexState.CHAR_QUOTE
                    current == '{' -> depth++
                    current == '}' -> {
                        depth--
                        if (depth == 0) return index + 1
                    }
                }
                SourceLexState.LINE_COMMENT -> if (current == '\n') {
                    state = SourceLexState.NORMAL
                }
                SourceLexState.BLOCK_COMMENT -> if (current == '*' && next == '/') {
                    state = SourceLexState.NORMAL
                    index++
                }
                SourceLexState.DOUBLE_QUOTE,
                SourceLexState.CHAR_QUOTE -> if (current == '\\') {
                    index++
                } else if (
                    (state == SourceLexState.DOUBLE_QUOTE && current == '"') ||
                        (state == SourceLexState.CHAR_QUOTE && current == '\'')
                ) {
                    state = SourceLexState.NORMAL
                }
                SourceLexState.TRIPLE_QUOTE -> if (
                    current == '"' && source.startsWith("\"\"\"", index)
                ) {
                    state = SourceLexState.NORMAL
                    index += 2
                }
            }
            index++
        }
        error("unterminated method body: $methodName")
    }

    private enum class SourceLexState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        DOUBLE_QUOTE,
        CHAR_QUOTE,
        TRIPLE_QUOTE
    }
}
