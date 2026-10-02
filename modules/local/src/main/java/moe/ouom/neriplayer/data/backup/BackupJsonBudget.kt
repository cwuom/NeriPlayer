package moe.ouom.neriplayer.data.backup

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.FilterReader
import java.io.FilterWriter
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.io.Writer

internal data class BackupJsonLimits(
    val maxMetadataCharacters: Long = 16L * 1024 * 1024,
    val maxMetadataObjects: Long = 65_536,
    val maxTokenCharacters: Long = 10L * 1024 * 1024,
    val maxStatisticsRecordCharacters: Long = 10L * 1024 * 1024,
    val maxStatisticsRecordObjects: Long = 65_536,
    val maxStatisticsRecordBytes: Long = 10L * 1024 * 1024
) {
    init {
        require(maxMetadataCharacters > 0) { "Backup metadata character budget must be positive" }
        require(maxMetadataObjects > 0) { "Backup metadata object budget must be positive" }
        require(maxTokenCharacters > 0) { "Backup token character budget must be positive" }
        require(maxStatisticsRecordCharacters > 0) { "Backup statistics record character budget must be positive" }
        require(maxStatisticsRecordObjects > 0) { "Backup statistics record object budget must be positive" }
        require(maxStatisticsRecordBytes > 0) { "Backup statistics record byte budget must be positive" }
    }
}

internal fun Reader.withBackupJsonBudget(limits: BackupJsonLimits, checkActive: () -> Unit): BackupBudgetReader =
    BackupBudgetReader(this, limits, checkActive)

internal fun Writer.withBackupJsonBudget(limits: BackupJsonLimits, checkActive: () -> Unit): Writer =
    BackupBudgetWriter(this, BackupJsonBudget(limits), checkActive)

internal class BackupBudgetReader(input: Reader, limits: BackupJsonLimits,
    private val checkActive: () -> Unit) : FilterReader(input) {
    private val budget = BackupJsonBudget(limits)
    var charactersRead = 0L
        private set

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        checkActive()
        val count = super.read(buffer, offset, length)
        for (index in offset until offset + count.coerceAtLeast(0)) budget.accept(buffer[index])
        charactersRead += count.coerceAtLeast(0)
        return count
    }

    override fun read(): Int {
        checkActive()
        return super.read().also {
            if (it >= 0) {
                budget.accept(it.toChar())
                charactersRead++
            }
        }
    }
}

private class BackupBudgetWriter(output: Writer, private val budget: BackupJsonBudget,
    private val checkActive: () -> Unit) : FilterWriter(output) {
    override fun write(buffer: CharArray, offset: Int, length: Int) {
        checkActive()
        for (index in offset until offset + length) budget.accept(buffer[index])
        out.write(buffer, offset, length)
    }

    override fun write(text: String, offset: Int, length: Int) {
        checkActive()
        for (index in offset until offset + length) budget.accept(text[index])
        out.write(text, offset, length)
    }

    override fun write(character: Int) {
        checkActive()
        budget.accept(character.toChar())
        out.write(character)
    }
}

// 字符进入 Gson 前先检查容量，格式、重复字段和文档结尾仍由原有读取流程验证
private class BackupJsonBudget(private val limits: BackupJsonLimits) {
    private enum class RootPosition { NAME, COLON, VALUE, AFTER_VALUE }

    private val tokens = BackupTokenBudget(limits.maxTokenCharacters)
    private val statistics = BackupStatisticsRecordBudget(limits)
    private var depth = 0
    private var rootPosition = RootPosition.NAME
    private var rootName: BackupRootFieldName? = null
    private var metadataField = false
    private var statisticsField = false
    private var metadataValue = false
    private var metadataCharacters = 0L
    private var metadataObjects = 0L
    private var rootFields = 0L
    private var rootNameCharacters = 0L

    fun accept(character: Char) {
        if (tokens.inString) {
            acceptStringCharacter(character)
            return
        }
        tokens.finishScalarAt(character)
        beginRootValue(character)
        statistics.accept(character, false, depth)
        countMetadata(character)
        if (character == '"') beginString()
        else if (isStructural(character)) acceptStructure(character)
        else if (!character.isWhitespace()) tokens.scalarCharacter()
    }

    private fun acceptStringCharacter(character: Char) {
        statistics.accept(character, true, depth)
        countMetadata(character)
        if (rootName != null) countRootNameCharacter()
        if (tokens.stringCharacter(character)) {
            rootName?.let { name ->
                val field = name.decoded()
                metadataField = field in METADATA_FIELDS
                statisticsField = field in STATISTICS_FIELDS
                rootPosition = RootPosition.COLON
            }
            rootName = null
        } else rootName?.append(character)
    }

    private fun beginString() {
        if (depth == 1 && rootPosition == RootPosition.NAME) {
            if (rootFields == MAX_ROOT_FIELDS) throw IOException("Backup root field budget exceeded")
            rootFields++
            rootName = BackupRootFieldName()
            countRootNameCharacter()
        }
        tokens.beginString()
    }

    private fun countRootNameCharacter() {
        if (rootNameCharacters == MAX_ROOT_NAME_CHARACTERS) throw IOException("Backup root name character budget exceeded")
        rootNameCharacters++
    }

    private fun beginRootValue(character: Char) {
        if (depth != 1 || rootPosition != RootPosition.VALUE || character.isWhitespace()) return
        metadataValue = metadataField
        if (statisticsField && character == '[') statistics.beginArray(depth + 1)
        rootPosition = RootPosition.AFTER_VALUE
    }

    private fun countMetadata(character: Char) {
        if (!metadataValue) return
        if (!tokens.inString && character.isWhitespace()) return
        if (!tokens.inString && depth == 1 && (character == ',' || character == '}')) return
        if (metadataCharacters == limits.maxMetadataCharacters) throw IOException("Backup metadata character budget exceeded")
        metadataCharacters++
    }

    private fun acceptStructure(character: Char) {
        when (character) {
            '{' -> beginObject()
            '[' -> depth++
            '}', ']' -> {
                if (depth == 1) metadataValue = false
                depth--
            }
            ':' -> if (depth == 1) rootPosition = RootPosition.VALUE
            ',' -> if (depth == 1) {
                metadataValue = false
                metadataField = false
                statisticsField = false
                rootPosition = RootPosition.NAME
            }
        }
    }

    private fun beginObject() {
        if (metadataValue) {
            if (metadataObjects == limits.maxMetadataObjects) throw IOException("Backup metadata object budget exceeded")
            metadataObjects++
        }
        if (depth == 0) rootPosition = RootPosition.NAME
        depth++
    }

    private companion object {
        const val MAX_ROOT_FIELDS = 65_536L
        const val MAX_ROOT_NAME_CHARACTERS = 16L * 1024 * 1024
        val METADATA_FIELDS = setOf("version", "timestamp", "playlists", "recentPlays", "playbackStatsClearedAt",
            "legacyLyricCandidates", "lyricOverrides", "exportDate")
        val STATISTICS_FIELDS = setOf("playbackStats", "playbackStatBuckets")
    }
}

// 页可以逐条写入，但一条 DTO 的所有字段和计数分片仍会一起分配
private class BackupStatisticsRecordBudget(private val limits: BackupJsonLimits) {
    private var arrayDepth = 0
    private var recordDepth = 0
    private var characters = 0L
    private var objects = 0L
    private var bytes = 0L
    private var previousHighSurrogate = false

    fun beginArray(depth: Int) {
        arrayDepth = depth
    }

    fun accept(character: Char, inString: Boolean, depth: Int) {
        if (!inString && character == '{') beginObject(depth)
        if (recordDepth != 0) countCharacter(character)
        if (inString) return
        if (character == '}' && depth == recordDepth) recordDepth = 0
        if (character == ']' && depth == arrayDepth) arrayDepth = 0
    }

    private fun beginObject(depth: Int) {
        if (arrayDepth != 0 && depth == arrayDepth) {
            recordDepth = depth + 1
            characters = 0
            objects = 0
            bytes = 0
            previousHighSurrogate = false
        }
        if (recordDepth == 0) return
        if (objects == limits.maxStatisticsRecordObjects) throw IOException("Backup statistics record object budget exceeded")
        objects++
    }

    private fun countCharacter(character: Char) {
        if (characters == limits.maxStatisticsRecordCharacters) throw IOException("Backup statistics record character budget exceeded")
        characters++
        countBytes(character)
    }

    private fun countBytes(character: Char) {
        val size = when {
            character <= '\u007f' -> 1L
            character <= '\u07ff' -> 2L
            previousHighSurrogate && character in '\uDC00'..'\uDFFF' -> 1L
            else -> 3L
        }
        if (bytes > limits.maxStatisticsRecordBytes - size) throw IOException("Backup statistics record byte budget exceeded")
        bytes += size
        // 高代理位先占三个字节，紧邻低代理位补一个，跨读取或写入分块也计为四个
        previousHighSurrogate = character in '\uD800'..'\uDBFF'
    }
}

private class BackupTokenBudget(private val limit: Long) {
    var inString = false
        private set
    private var escaped = false
    private var characters = 0L

    fun beginString() {
        characters = 0
        inString = true
        addCharacter()
    }

    fun stringCharacter(character: Char): Boolean {
        addCharacter()
        if (escaped) escaped = false
        else if (character == '\\') escaped = true
        else if (character == '"') {
            inString = false
            characters = 0
            return true
        }
        return false
    }

    fun finishScalarAt(character: Char) {
        if (character.isWhitespace() || isStructural(character) || character == '"') characters = 0
    }

    fun scalarCharacter() { addCharacter() }

    private fun addCharacter() {
        if (characters == limit) throw IOException("Backup token character budget exceeded")
        characters++
    }
}

private class BackupRootFieldName {
    private val raw = StringBuilder()
    private var tooLong = false

    fun append(character: Char) {
        if (raw.length == MAX_FIELD_CHARACTERS) tooLong = true
        if (!tooLong) raw.append(character)
    }

    fun decoded(): String? {
        if (tooLong) return null
        return JsonReader(StringReader("\"$raw\"")).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.nextString()
        }
    }

    companion object {
        // 已知字段即使每个字母都写成 Unicode 转义，也远小于这个固定缓冲区
        private const val MAX_FIELD_CHARACTERS = 512
    }
}

private fun isStructural(character: Char): Boolean = character in "{}[]:,"
