package moe.ouom.neriplayer.data.sync.archive.compact

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_BODIES
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_BODY_BYTES
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_GROUP_BYTES
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_PALETTE
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.pack
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.strictText
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.writePart
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.writeUnsigned

internal object SyncLyricBodyCodec {
    private val timestamps = Regex("\\[[0-9]{1,18}:[0-9]{1,18}[.:][0-9]{1,18}\\]|\\([0-9]{1,18},[0-9]{1,18},[0-9]{1,18}\\)|\\[[0-9]{1,18},[0-9]{1,18}\\]")
    private val digits = Regex("[0-9]+")

    fun encode(bodies: List<ByteArray>, workspace: File, checkActive: () -> Unit): List<File> {
        validateBodies(bodies, checkActive)
        val structured = structure(bodies, checkActive)
        val unicode = unicodeParts(structured[2], checkActive)
        val parts = structured.take(2) + (unicode ?: listOf(structured[2]))
        return writeParts(if (unicode == null) 0 else 1, parts, workspace, checkActive)
    }

    fun decode(input: InputStream, workspace: File, checkActive: () -> Unit): List<ByteArray> {
        require(workspace.isDirectory) { "Lyric workspace is unavailable" }
        return SyncLyricBodyReader.read(input, checkActive)
    }

    private fun validateBodies(bodies: List<ByteArray>, checkActive: () -> Unit) {
        require(bodies.size <= MAX_BODIES) { "Lyric body group exceeds record budget" }
        var bytes = 0L
        for (body in bodies) {
            checkActive()
            require(body.size <= MAX_BODY_BYTES) { "Lyric body exceeds byte budget" }
            bytes += body.size
            require(bytes <= MAX_GROUP_BYTES) { "Lyric body group exceeds byte budget" }
        }
    }

    private fun structure(bodies: List<ByteArray>, checkActive: () -> Unit): List<ByteArray> {
        val layout = ByteArrayOutputStream()
        val literals = ByteArrayOutputStream()
        val columns = List(8) { ByteArrayOutputStream() }
        writeUnsigned(layout, bodies.size.toLong())
        for (body in bodies) {
            checkActive()
            val searchable = String(body, Charsets.ISO_8859_1)
            var count = 0
            timestamps.findAll(searchable).forEach { if (count++ % 256 == 0) checkActive() }
            writeUnsigned(layout, count.toLong())
            var start = 0
            val previous = LongArray(8)
            for ((index, match) in timestamps.findAll(searchable).withIndex()) {
                if (index % 256 == 0) checkActive()
                writeLiteral(layout, literals, body, start, match.range.first)
                writeTimestamp(layout, columns, previous, match.value)
                start = match.range.last + 1
            }
            writeLiteral(layout, literals, body, start, body.size)
        }
        return listOf(layout.toByteArray(), pack(columns.map { it.toByteArray() }), literals.toByteArray())
    }

    private fun writeLiteral(layout: OutputStream, literals: OutputStream, body: ByteArray, start: Int, end: Int) {
        writeUnsigned(layout, (end - start).toLong())
        literals.write(body, start, end - start)
    }

    private fun writeTimestamp(layout: OutputStream, columns: List<ByteArrayOutputStream>, previous: LongArray, token: String) {
        val kind = when { ':' in token -> 0; token.startsWith('(') -> 1; else -> 2 }
        layout.write(kind)
        if (kind == 0) layout.write(if ('.' in token) 1 else 0)
        val offset = kind * 3
        for ((index, part) in digits.findAll(token).withIndex()) {
            writeUnsigned(layout, part.value.length.toLong())
            val number = part.value.toLong()
            val slot = offset + index
            val delta = number - previous[slot]
            writeUnsigned(columns[slot], if (index == 0) (delta shl 1) xor (delta shr 63) else number)
            previous[slot] = number
        }
    }

    private fun unicodeParts(raw: ByteArray, checkActive: () -> Unit): List<ByteArray>? {
        if (raw.isEmpty()) return null
        val text = strictText(raw) ?: return null
        val frequencies = HashMap<Int, Int>()
        var position = 0
        var characterCount = 0
        while (position < text.length) {
            if (characterCount++ % 4096 == 0) checkActive()
            val codePoint = text.codePointAt(position)
            frequencies[codePoint] = (frequencies[codePoint] ?: 0) + 1
            if (frequencies.size > MAX_PALETTE) return null
            position += Character.charCount(codePoint)
        }
        val alphabet = frequencies.keys.sortedWith(compareByDescending<Int> { frequencies.getValue(it) }.thenBy { it })
        val indices = alphabet.withIndex().associate { it.value to it.index }
        val palette = pack(alphabet.map { String(Character.toChars(it)).toByteArray(Charsets.UTF_8) })
        val lengths = ByteArrayOutputStream().also { writeUnsigned(it, 1); writeUnsigned(it, characterCount.toLong()) }.toByteArray()
        val content = ByteArrayOutputStream(characterCount * 2)
        position = 0
        var visited = 0
        while (position < text.length) {
            if (visited++ % 4096 == 0) checkActive()
            val codePoint = text.codePointAt(position)
            val index = indices.getValue(codePoint)
            content.write(index and 255)
            content.write(index ushr 8)
            position += Character.charCount(codePoint)
        }
        return listOf(palette, lengths, content.toByteArray())
    }

    private fun writeParts(mode: Int, parts: List<ByteArray>, workspace: File, checkActive: () -> Unit): List<File> {
        require(workspace.isDirectory) { "Lyric workspace is unavailable" }
        val created = ArrayList<File>()
        try {
            val groups = partGroups(mode, parts)
            for ((index, group) in groups.withIndex()) {
                checkActive()
                val file = File.createTempFile("lyric-body-", ".part", workspace)
                created += file
                file.outputStream().use { output ->
                    if (index == 0) {
                        output.write(mode)
                        writeUnsigned(output, parts.size.toLong())
                    }
                    for (part in group) writePart(output, part, checkActive)
                }
            }
            checkActive()
            return created
        } catch (failure: Throwable) {
            removeCreatedParts(created, failure)
            throw failure
        }
    }

    private fun partGroups(mode: Int, parts: List<ByteArray>): List<List<ByteArray>> = if (mode == 0) {
        parts.map { listOf(it) }
    } else {
        // 调色板和字符长度共同描述字典，保持同一块有利于增量复用
        listOf(listOf(parts[0]), listOf(parts[1]), listOf(parts[2], parts[3]), listOf(parts[4]))
    }

    private fun removeCreatedParts(created: List<File>, failure: Throwable) {
        for (file in created) {
            file.delete()
            if (file.exists()) {
                failure.addSuppressed(IllegalStateException("Unable to remove lyric body part"))
            }
        }
    }
}
