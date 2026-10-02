package moe.ouom.neriplayer.data.sync.archive.compact

import moe.ouom.neriplayer.data.sync.archive.SyncArchiveStaging
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal object SyncArchiveCompactRecords {
    private val MAIN_MAGIC = "NPCOMP01".toByteArray(Charsets.US_ASCII)
    private val LEGACY_MAGIC = "NPORDR01".toByteArray(Charsets.US_ASCII)
    class Packed internal constructor(
        val mainParts: List<File>,
        val legacyParts: List<File>,
        val poolParts: List<File>,
        private val directory: File
    ) : Closeable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            check(directory.deleteRecursively()) { "Unable to release compact archive staging" }
        }
    }

    fun pack(
        main: InputStream,
        legacy: InputStream,
        workspace: File,
        checkActive: () -> Unit
    ): Packed {
        checkActive()
        val directory = SyncArchiveStaging.createDirectory(workspace, "compact-")
        try {
            SyncLyricPoolFiles(directory, checkActive).use { pool ->
                val mainParts = ArrayList<File>()
                val legacyParts = ArrayList<File>()
                var block = SyncCompactBlock(pool, checkActive)
                fun flush() {
                    if (block.count == 0) return
                    val encoded = block.encode()
                    val mainPart = File(directory, "main-${mainParts.size}")
                    mainPart.outputStream().buffered().use { output ->
                        if (mainParts.isEmpty()) output.write(MAIN_MAGIC)
                        output.write(0)
                        SyncCompactWire.writePart(output, encoded.main)
                    }
                    val legacyPart = File(directory, "legacy-${legacyParts.size}")
                    legacyPart.outputStream().buffered().use { output ->
                        if (legacyParts.isEmpty()) output.write(LEGACY_MAGIC)
                        SyncCompactWire.writePart(output, encoded.legacy)
                    }
                    mainParts += mainPart
                    legacyParts += legacyPart
                    block = SyncCompactBlock(pool, checkActive)
                }
                fun literal(input: InputStream, kind: Int, length: Int, legacyRecord: Boolean) {
                    flush()
                    val part = File(directory, "main-${mainParts.size}")
                    part.outputStream().buffered().use { output ->
                        if (mainParts.isEmpty()) output.write(MAIN_MAGIC)
                        output.write(if (legacyRecord) 2 else 1)
                        output.write(kind)
                        SyncCompactWire.writeNumber(output, length.toLong())
                        copy(input, output, length.toLong(), checkActive)
                    }
                    mainParts += part
                }
                fun consume(input: InputStream, legacyRecord: Boolean) {
                    val frames = DataInputStream(input)
                    while (true) {
                        checkActive()
                        val kind = frames.read()
                        if (kind < 0) break
                        val length = frames.readInt()
                        require(length in 0..SyncCompactWire.MAX_RECORD_BYTES) { "Original record exceeds safe budget" }
                        if (length > SyncCompactWire.BLOCK_BYTES) {
                            literal(frames, kind, length, legacyRecord)
                            continue
                        }
                        val raw = SyncCompactWire.bytes(frames, length)
                        val parsed = try { block.parse(kind, raw) } catch (_: CompactLiteralRequired) {
                            literal(raw.inputStream(), kind, length, legacyRecord)
                            continue
                        }
                        if (!block.accepts(parsed)) flush()
                        if (!block.accepts(parsed)) literal(raw.inputStream(), kind, length, legacyRecord)
                        else block.add(parsed, legacyRecord)
                    }
                }
                consume(main, false)
                consume(legacy, true)
                flush()
                if (mainParts.isEmpty()) mainParts += File(directory, "main-empty").apply { writeBytes(MAIN_MAGIC) }
                if (legacyParts.isEmpty()) legacyParts += File(directory, "legacy-empty").apply { writeBytes(LEGACY_MAGIC) }
                return Packed(mainParts, legacyParts, pool.encode(), directory)
            }
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    fun unpack(
        main: InputStream,
        legacy: InputStream,
        pool: InputStream,
        mainOutput: OutputStream,
        legacyOutput: OutputStream,
        expectedMainBytes: Long,
        expectedLegacyBytes: Long,
        workspace: File? = null,
        checkActive: () -> Unit
    ) {
        checkActive()
        val maximumPoolBytes = streamBudget(expectedMainBytes, expectedLegacyBytes)
        val directory = SyncArchiveStaging.createDirectory(workspace, "compact-read-")
        try {
            SyncLyricPoolFiles.decode(pool, directory, maximumPoolBytes, checkActive).use { index ->
                require(SyncCompactWire.bytes(main, MAIN_MAGIC.size).contentEquals(MAIN_MAGIC)) { "Unknown compact record version" }
                require(SyncCompactWire.bytes(legacy, LEGACY_MAGIC.size).contentEquals(LEGACY_MAGIC)) { "Unknown compact order version" }
                RecordReader(main, legacy, index, mainOutput, legacyOutput, expectedMainBytes, expectedLegacyBytes, checkActive).restore()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun streamBudget(main: Long, legacy: Long): Long {
        require(main >= 0) { "Invalid main stream budget" }
        require(legacy >= 0) { "Invalid legacy stream budget" }
        require(main <= Long.MAX_VALUE - legacy) { "Original stream budget overflow" }
        return main + legacy
    }

    private class RecordReader(private val main: InputStream, private val legacy: InputStream,
                               private val pool: SyncLyricPoolFiles.Index, mainOutput: OutputStream,
                               legacyOutput: OutputStream, mainBytes: Long, legacyBytes: Long,
                               private val checkActive: () -> Unit) {
        private val mainOutput = CountedOutput(mainOutput, mainBytes)
        private val legacyOutput = CountedOutput(legacyOutput, legacyBytes)

        fun restore() {
            while (true) {
                checkActive()
                val mode = main.read()
                if (mode < 0) break
                when (mode) {
                    0 -> SyncCompactBlock.decode(SyncCompactWire.part(main), SyncCompactWire.part(legacy),
                        pool, mainOutput, legacyOutput, checkActive)
                    1, 2 -> literal(mode)
                    else -> error("Unknown compact block mode")
                }
            }
            SyncCompactWire.exhausted(legacy)
            mainOutput.finish()
            legacyOutput.finish()
        }

        private fun literal(mode: Int) {
            val kind = main.read()
            require(kind >= 0) { "Truncated compact literal kind" }
            val length = SyncCompactWire.count(main, SyncCompactWire.MAX_RECORD_BYTES)
            val output = if (mode == 1) mainOutput else legacyOutput
            val frame = DataOutputStream(output)
            frame.writeByte(kind)
            frame.writeInt(length)
            copy(main, output, length.toLong(), checkActive)
        }
    }

    private fun copy(input: InputStream, output: OutputStream, length: Long, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            checkActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count >= 0) { "Truncated original record payload" }
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private class CountedOutput(private val output: OutputStream, private val maximum: Long) : OutputStream() {
        var count = 0L
            private set

        fun finish() = require(count == maximum) { "Restored compact stream length mismatch" }

        override fun write(value: Int) {
            require(count < maximum) { "Restored stream exceeds original budget" }
            output.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length.toLong() <= maximum - count) { "Restored stream exceeds original budget" }
            output.write(bytes, offset, length)
            count += length
        }
    }
}
