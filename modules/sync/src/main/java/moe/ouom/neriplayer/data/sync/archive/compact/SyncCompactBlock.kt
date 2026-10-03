package moe.ouom.neriplayer.data.sync.archive.compact

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.TreeMap

internal class SyncCompactBlock(private val pool: SyncLyricPoolFiles, private val checkActive: () -> Unit) {
    data class Parsed(val kind: Int, val message: Message, val metadataBytes: Int, val fieldCount: Int)
    data class Message(val context: String, val fields: List<Value>)
    data class Value(val key: Long, val body: ByteArray?, val lyric: SyncLyricPoolFiles.Ref?, val child: Message?)
    data class Encoded(val main: ByteArray, val legacy: ByteArray)
    private data class Token(val key: Long, val body: ByteArray)
    private class Table(val context: String) { val rows = ArrayList<MutableList<Token>>() }
    private class Key(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    private class Entry(var body: ByteArray?, var lyric: SyncLyricPoolFiles.Ref?, var index: Int = 0)
    private val dictionary = HashMap<Key, Entry>()
    private val tables = TreeMap<String, Table>()
    private val mainOrder = ArrayList<Pair<Int, Int>>()
    private val legacyOrder = ArrayList<Pair<Int, Int>>()
    var metadataBytes = 0
        private set
    var fieldCount = 0
        private set
    val count: Int get() = mainOrder.size + legacyOrder.size

    fun parse(kind: Int, raw: ByteArray): Parsed {
        val parser = MessageParser()
        val message = parser.message(raw, SyncCompactWire.root(kind), 0)
        return Parsed(kind, message, parser.metadata + 5, parser.fields)
    }

    private inner class MessageParser {
        var metadata = 0
            private set
        var fields = 0
            private set

        fun message(bytes: ByteArray, context: String, depth: Int): Message {
            if (depth > SyncCompactWire.MAX_DEPTH) throw CompactLiteralRequired()
            val values = SyncCompactWire.fields(bytes).map { field ->
                checkActive()
                fields++
                if (fields > SyncCompactWire.MAX_FIELDS) throw CompactLiteralRequired()
                value(field, context, depth)
            }
            return Message(context, values)
        }

        private fun value(field: SyncCompactWire.Field, context: String, depth: Int): Value {
            val tag = (field.key ushr 3).toInt()
            val nested = if (field.key and 7 == 2L) SyncCompactWire.nested(context, tag) else null
            val prefix = prefixBytes(field)
            if (nested != null) {
                metadata += prefix
                return Value(field.key, null, null, message(field.body, nested, depth + 1))
            }
            if (field.key and 7 == 2L && SyncCompactWire.isLyric(context, tag)) {
                metadata += prefix + 32
                return Value(field.key, null, pool.add(field.body), null)
            }
            metadata += prefix + field.body.size
            return Value(field.key, field.body, null, null)
        }

        private fun prefixBytes(field: SyncCompactWire.Field): Int {
            if (field.literal) return 0
            val key = SyncCompactWire.number(field.key).size
            return key + if (field.key and 7 == 2L) SyncCompactWire.number(field.body.size.toLong()).size else 0
        }
    }

    fun accepts(record: Parsed): Boolean = count < SyncCompactWire.MAX_ROWS &&
        metadataBytes.toLong() + record.metadataBytes <= SyncCompactWire.BLOCK_BYTES &&
        fieldCount.toLong() + record.fieldCount <= SyncCompactWire.MAX_FIELDS

    fun add(record: Parsed, legacy: Boolean) {
        metadataBytes += record.metadataBytes
        fieldCount += record.fieldCount
        observe(record.message)
        val row = column(record.message)
        (if (legacy) legacyOrder else mainOrder) += record.kind to row
    }

    private fun observe(message: Message) {
        for (field in message.fields) {
            if (field.child != null) observe(field.child)
            else if (field.key and 7 == 2L) intern(field)
        }
    }

    private fun intern(field: Value) {
        val key = Key(fieldHash(field))
        val existing = dictionary[key]
        if (existing == null) {
            dictionary[key] = Entry(field.body, field.lyric)
            return
        }
        require(fieldBody(field).contentEquals(entryBody(existing))) { "Conflicting compact dictionary hash" }
        if (field.lyric != null) {
            existing.body = null
            existing.lyric = field.lyric
        }
    }

    private fun fieldHash(field: Value): ByteArray = field.lyric?.hash ?:
        MessageDigest.getInstance("SHA-256").digest(requireNotNull(field.body))

    private fun fieldBody(field: Value): ByteArray = field.body ?: pool.read(requireNotNull(field.lyric))

    private fun column(message: Message): Int {
        val table = tables.getOrPut(message.context) { Table(message.context) }
        val index = table.rows.size
        val row = ArrayList<Token>()
        table.rows += row
        for (field in message.fields) {
            val body = when {
                field.child != null -> SyncCompactWire.number(column(field.child).toLong())
                field.key and 7 == 2L -> {
                    val hash = field.lyric?.hash ?: MessageDigest.getInstance("SHA-256").digest(requireNotNull(field.body))
                    // 索引在完整局部字典排序后再写入，避免读入顺序影响短引用编号
                    hash
                }
                else -> requireNotNull(field.body)
            }
            row += Token(field.key, body)
        }
        return index
    }

    fun encode(): Encoded {
        checkActive()
        val entries = dictionary.entries.sortedWith { left, right ->
            val type = (left.value.lyric != null).compareTo(right.value.lyric != null)
            if (type != 0) type else SyncCompactWire.compare(entryBody(left.value), entryBody(right.value))
        }
        val dictionaryBytes = writeDictionary(entries)
        remapLeafReferences()
        val (shape, values) = encodeColumns()
        val main = ByteArrayOutputStream()
        writeField(main, 1, order(mainOrder))
        writeField(main, 2, shape)
        writeField(main, 3, values)
        writeField(main, 4, dictionaryBytes)
        return Encoded(main.toByteArray(), order(legacyOrder))
    }

    private fun writeDictionary(entries: List<Map.Entry<Key, Entry>>): ByteArray {
        val output = ByteArrayOutputStream()
        SyncCompactWire.writeNumber(output, entries.size.toLong())
        entries.forEachIndexed { index, entry ->
            checkActive()
            entry.value.index = index + 1
            writeDictionaryEntry(output, entry.value)
        }
        return output.toByteArray()
    }

    private fun writeDictionaryEntry(output: OutputStream, entry: Entry) {
        val lyric = entry.lyric
        output.write(if (lyric == null) 0 else 1)
        if (lyric != null) output.write(lyric.hash)
        val length = lyric?.length ?: requireNotNull(entry.body).size
        SyncCompactWire.writeNumber(output, length.toLong())
        if (lyric == null) output.write(requireNotNull(entry.body))
    }

    private fun remapLeafReferences() {
        for (table in tables.values) for (row in table.rows) for (index in row.indices) {
            val token = row[index]
            if (token.key and 7 != 2L) continue
            if (SyncCompactWire.nested(table.context, (token.key ushr 3).toInt()) != null) continue
            val ref = requireNotNull(dictionary[Key(token.body)]).index
            row[index] = Token(token.key, SyncCompactWire.number(ref.toLong() shl 1))
        }
    }

    private fun entryBody(entry: Entry): ByteArray = entry.body ?: pool.read(requireNotNull(entry.lyric))

    private fun encodeColumns(): Pair<ByteArray, ByteArray> {
        val shared = sharedNumbers()
        val indexes = shared.withIndex().associate { it.value to it.index + 1 }
        val shape = ByteArrayOutputStream()
        val values = ByteArrayOutputStream()
        writeSharedNumbers(values, shared)
        SyncCompactWire.writeNumber(shape, tables.size.toLong())
        for (table in tables.values) {
            checkActive()
            SyncCompactWire.writePart(shape, table.context.toByteArray(Charsets.US_ASCII))
            SyncCompactWire.writeNumber(shape, table.rows.size.toLong())
            val columns = writeRows(shape, table.rows)
            SyncCompactWire.writeNumber(shape, columns.size.toLong())
            for ((key, bodies) in columns) writeColumn(shape, values, key, bodies, indexes)
        }
        return shape.toByteArray() to values.toByteArray()
    }

    private fun sharedNumbers(): List<Long> {
        val frequencies = TreeMap<Long, Int>()
        for (table in tables.values) for (row in table.rows) for (token in row) {
            sharedNumber(token)?.let { frequencies[it] = (frequencies[it] ?: 0) + 1 }
        }
        return frequencies.filterValues { it > 1 }.keys.toList()
    }

    private fun sharedNumber(token: Token): Long? {
        if (token.key == 0L || token.key and 7 != 0L || token.body.size <= 2) return null
        return canonicalNumber(token.body)
    }

    private fun writeSharedNumbers(output: OutputStream, shared: List<Long>) {
        SyncCompactWire.writeNumber(output, shared.size.toLong())
        var previous = 0L
        for (number in shared) {
            SyncCompactWire.writeNumber(output, SyncCompactWire.zigzag(number - previous))
            previous = number
        }
    }

    private fun writeRows(shape: OutputStream, rows: List<List<Token>>): Map<Long, MutableList<ByteArray>> {
        val columns = TreeMap<Long, MutableList<ByteArray>>()
        for (row in rows) {
            SyncCompactWire.writeNumber(shape, row.size.toLong())
            for (token in row) {
                SyncCompactWire.writeNumber(shape, token.key)
                columns.getOrPut(token.key) { ArrayList() } += token.body
            }
        }
        return columns
    }

    private fun writeColumn(shape: OutputStream, values: OutputStream, key: Long,
                            bodies: List<ByteArray>, indexes: Map<Long, Int>) {
        val numeric = key != 0L && key and 7 == 0L && bodies.all { canonicalNumber(it) != null }
        val bytes = if (numeric) writeNumericColumn(bodies, indexes) else ByteArrayOutputStream().also { output ->
            bodies.forEach { SyncCompactWire.writePart(output, it) }
        }.toByteArray()
        SyncCompactWire.writeNumber(shape, key)
        SyncCompactWire.writeNumber(shape, bodies.size.toLong())
        shape.write(if (numeric) 2 else 0)
        SyncCompactWire.writeNumber(shape, bytes.size.toLong())
        values.write(bytes)
    }

    private fun writeNumericColumn(bodies: List<ByteArray>, indexes: Map<Long, Int>): ByteArray {
        val output = ByteArrayOutputStream()
        var previous = 0L
        for (body in bodies) {
            val current = requireNotNull(canonicalNumber(body))
            val reference = indexes[current] ?: 0
            SyncCompactWire.writeNumber(output, reference.toLong())
            if (reference == 0) SyncCompactWire.writeNumber(output, SyncCompactWire.zigzag(current - previous))
            previous = current
        }
        return output.toByteArray()
    }

    companion object {
        private data class DictionaryValue(val bytes: ByteArray?, val hash: ByteArray?, val length: Int)

        private fun canonicalNumber(body: ByteArray): Long? {
            val input = ByteArrayInputStream(body)
            return try {
                val value = SyncCompactWire.readNumber(input, false)
                if (input.available() == 0 && body.contentEquals(SyncCompactWire.number(value))) value else null
            } catch (_: IllegalArgumentException) { null }
        }

        private fun order(records: List<Pair<Int, Int>>): ByteArray {
            val output = ByteArrayOutputStream()
            val previous = LongArray(256)
            SyncCompactWire.writeNumber(output, records.size.toLong())
            for ((kind, row) in records) {
                output.write(kind)
                SyncCompactWire.writeNumber(output, SyncCompactWire.zigzag(row - previous[kind]))
                previous[kind] = row.toLong()
            }
            return output.toByteArray()
        }

        private fun writeField(output: OutputStream, tag: Int, bytes: ByteArray) {
            SyncCompactWire.writeNumber(output, (tag.toLong() shl 3) or 2)
            SyncCompactWire.writePart(output, bytes)
        }

        fun decode(main: ByteArray, legacy: ByteArray, pool: SyncLyricPoolFiles.Index,
                   mainOutput: OutputStream, legacyOutput: OutputStream, checkActive: () -> Unit) {
            val fields = SyncCompactWire.fields(main)
            require(fields.size == 4 && fields.map { it.key } == listOf(10L, 18L, 26L, 34L)) { "Invalid compact block sections" }
            val dictionary = readDictionary(fields[3].body)
            val tables = readColumns(fields[1].body, fields[2].body, checkActive)
            restoreOrder(fields[0].body, tables, dictionary, pool, mainOutput, checkActive)
            restoreOrder(legacy, tables, dictionary, pool, legacyOutput, checkActive)
        }

        private val contexts = ((0..255).map { "kind$it" } + listOf("song", "token", "shard")).toSet()

        private fun readDictionary(raw: ByteArray): List<DictionaryValue> {
            val input = ByteArrayInputStream(raw)
            val count = SyncCompactWire.count(input, SyncCompactWire.MAX_FIELDS)
            val result = ArrayList<DictionaryValue>(count)
            var metadata = 0L
            repeat(count) {
                val entry = readDictionaryEntry(input)
                metadata += entry.bytes?.size ?: 32
                require(metadata <= SyncCompactWire.BLOCK_BYTES) { "Compact dictionary exceeds metadata budget" }
                result += entry
            }
            SyncCompactWire.exhausted(input)
            return result
        }

        private fun readDictionaryEntry(input: ByteArrayInputStream): DictionaryValue {
            val flag = input.read()
            require(flag == 0 || flag == 1) { "Invalid compact dictionary mode" }
            val hash = if (flag == 1) SyncCompactWire.bytes(input, 32) else null
            val length = SyncCompactWire.count(input, SyncCompactWire.BLOCK_BYTES)
            val bytes = if (flag == 0) SyncCompactWire.bytes(input, length) else null
            return DictionaryValue(bytes, hash, length)
        }

        private class TableBudget {
            private var rows = 0
            private var fields = 0

            fun rows(count: Int) {
                rows += count
                // 根记录和每个嵌套字段各自占一行，两种计数需要共同覆盖
                require(rows <= SyncCompactWire.MAX_FIELDS + SyncCompactWire.MAX_ROWS) { "Compact row budget exceeded" }
            }

            fun fields(count: Int) {
                fields += count
                require(fields <= SyncCompactWire.MAX_FIELDS) { "Compact field budget exceeded" }
            }
        }

        private fun readColumns(shapeRaw: ByteArray, valuesRaw: ByteArray, checkActive: () -> Unit): Map<String, Table> {
            val shape = ByteArrayInputStream(shapeRaw)
            val values = ByteArrayInputStream(valuesRaw)
            val shared = readSharedNumbers(values)
            val tables = HashMap<String, Table>()
            val budget = TableBudget()
            repeat(SyncCompactWire.count(shape, contexts.size)) {
                checkActive()
                val table = readTable(shape, values, shared, tables.keys, budget)
                tables[table.context] = table
            }
            SyncCompactWire.exhausted(shape)
            SyncCompactWire.exhausted(values)
            return tables
        }

        private fun readSharedNumbers(values: ByteArrayInputStream): LongArray {
            val count = SyncCompactWire.count(values, SyncCompactWire.MAX_FIELDS)
            val shared = LongArray(count)
            var previous = 0L
            for (index in shared.indices) {
                previous += SyncCompactWire.unzigzag(SyncCompactWire.readNumber(values))
                shared[index] = previous
            }
            return shared
        }

        private fun readTable(shape: ByteArrayInputStream, values: ByteArrayInputStream, shared: LongArray,
                              existing: Set<String>, budget: TableBudget): Table {
            val context = SyncCompactWire.part(shape, 16).toString(Charsets.US_ASCII)
            require(context in contexts && context !in existing) { "Invalid compact table context" }
            val table = Table(context)
            readRows(shape, table, budget)
            val expected = HashMap<Long, Int>()
            for (row in table.rows) for (token in row) expected[token.key] = (expected[token.key] ?: 0) + 1
            val columns = readColumnsForTable(shape, values, shared, expected)
            populateRows(table, columns)
            return table
        }

        private fun readRows(shape: ByteArrayInputStream, table: Table, budget: TableBudget) {
            val count = SyncCompactWire.count(shape, SyncCompactWire.MAX_FIELDS)
            budget.rows(count)
            repeat(count) {
                val fields = SyncCompactWire.count(shape, SyncCompactWire.MAX_FIELDS)
                budget.fields(fields)
                table.rows += MutableList(fields) { Token(SyncCompactWire.readNumber(shape), byteArrayOf()) }
            }
        }

        private fun populateRows(table: Table, columns: Map<Long, ArrayDeque<ByteArray>>) {
            for (row in table.rows) for (index in row.indices) {
                val token = row[index]
                val body = columns.getValue(token.key).removeFirst()
                row[index] = Token(token.key, body)
            }
        }

        private data class Column(val key: Long, val count: Int, val numeric: Boolean, val bytes: ByteArray)

        private fun readColumnsForTable(shape: ByteArrayInputStream, values: ByteArrayInputStream,
                                        shared: LongArray, expected: Map<Long, Int>): Map<Long, ArrayDeque<ByteArray>> {
            val result = HashMap<Long, ArrayDeque<ByteArray>>()
            val count = SyncCompactWire.count(shape, expected.size)
            require(count == expected.size) { "Missing compact column" }
            repeat(count) {
                val column = readColumn(shape, values, expected, result.keys)
                result[column.key] = readColumnValues(column, shared)
            }
            return result
        }

        private fun readColumn(shape: ByteArrayInputStream, values: ByteArrayInputStream,
                               expected: Map<Long, Int>, existing: Set<Long>): Column {
            val key = SyncCompactWire.readNumber(shape)
            validateColumnKey(key)
            require(key !in existing) { "Duplicate compact column" }
            val count = SyncCompactWire.count(shape, SyncCompactWire.MAX_FIELDS)
            require(count == expected[key]) { "Compact column count mismatch" }
            val mode = shape.read()
            validateNumericMode(mode, key)
            val size = SyncCompactWire.count(shape, SyncCompactWire.MAX_PART_BYTES)
            return Column(key, count, mode == 2, SyncCompactWire.bytes(values, size))
        }

        private fun validateColumnKey(key: Long) {
            if (key == 0L) return
            require(key ushr 3 in 1..536870911L) { "Invalid compact column tag" }
            require(key and 7 in listOf(0L, 1L, 2L, 5L)) { "Invalid compact column wire type" }
        }

        private fun validateNumericMode(mode: Int, key: Long) {
            if (mode == 0) return
            require(mode == 2 && key != 0L && key and 7 == 0L) { "Invalid compact numeric mode" }
        }

        private fun readColumnValues(column: Column, shared: LongArray): ArrayDeque<ByteArray> {
            val input = ByteArrayInputStream(column.bytes)
            val result = ArrayDeque<ByteArray>()
            var previous = 0L
            repeat(column.count) {
                val body = if (column.numeric) {
                    previous = readNumericValue(input, shared, previous)
                    SyncCompactWire.number(previous)
                } else SyncCompactWire.part(input, SyncCompactWire.BLOCK_BYTES)
                result += body
            }
            SyncCompactWire.exhausted(input)
            return result
        }

        private fun readNumericValue(input: ByteArrayInputStream, shared: LongArray, previous: Long): Long {
            val reference = SyncCompactWire.count(input, shared.size)
            return if (reference != 0) shared[reference - 1]
                else previous + SyncCompactWire.unzigzag(SyncCompactWire.readNumber(input))
        }

        private fun restoreOrder(raw: ByteArray, tables: Map<String, Table>, dictionary: List<DictionaryValue>,
                                 pool: SyncLyricPoolFiles.Index, output: OutputStream, checkActive: () -> Unit) {
            val input = ByteArrayInputStream(raw)
            val count = SyncCompactWire.count(input, SyncCompactWire.MAX_ROWS)
            val previous = LongArray(256)
            val restorer = MessageRestorer(tables, dictionary, pool)
            val frame = DataOutputStream(output)
            repeat(count) {
                checkActive()
                val kind = input.read()
                require(kind >= 0) { "Truncated compact record order" }
                val row = orderRow(input, previous, kind)
                val body = restorer.message(SyncCompactWire.root(kind), row)
                frame.writeByte(kind)
                frame.writeInt(body.size)
                frame.write(body)
            }
            SyncCompactWire.exhausted(input)
        }

        private fun orderRow(input: ByteArrayInputStream, previous: LongArray, kind: Int): Int {
            previous[kind] += SyncCompactWire.unzigzag(SyncCompactWire.readNumber(input))
            require(previous[kind] in 0..Int.MAX_VALUE.toLong()) { "Invalid compact record index" }
            return previous[kind].toInt()
        }

        private class MessageRestorer(private val tables: Map<String, Table>,
                                      private val dictionary: List<DictionaryValue>,
                                      private val pool: SyncLyricPoolFiles.Index) {
            // 子消息类型来自固定的 V3 字段映射，引用不能形成同类型循环
            fun message(context: String, row: Int): ByteArray {
                val table = tables[context] ?: error("Missing compact context")
                require(row in table.rows.indices) { "Invalid compact child index" }
                val output = BoundedBytes(SyncCompactWire.BLOCK_BYTES)
                for (token in table.rows[row]) writeField(output, context, token)
                return output.toByteArray()
            }

            private fun writeField(output: OutputStream, context: String, token: Token) {
                if (token.key == 0L) { output.write(token.body); return }
                SyncCompactWire.writeNumber(output, token.key)
                if (token.key and 7 != 2L) { output.write(token.body); return }
                val body = referencedBody(context, token)
                SyncCompactWire.writeNumber(output, body.size.toLong())
                output.write(body)
            }

            private fun referencedBody(context: String, token: Token): ByteArray {
                val input = ByteArrayInputStream(token.body)
                val reference = SyncCompactWire.readNumber(input)
                val nested = SyncCompactWire.nested(context, (token.key ushr 3).toInt())
                val body = if (nested != null) {
                    require(reference in 0..Int.MAX_VALUE.toLong()) { "Invalid compact child reference" }
                    message(nested, reference.toInt())
                } else dictionaryBody(reference)
                SyncCompactWire.exhausted(input)
                return body
            }

            private fun dictionaryBody(reference: Long): ByteArray {
                require(reference and 1 == 0L) { "Invalid compact dictionary reference mode" }
                require(reference ushr 1 in 1..dictionary.size.toLong()) { "Invalid compact dictionary reference" }
                val entry = dictionary[(reference ushr 1).toInt() - 1]
                return entry.bytes ?: pool.find(requireNotNull(entry.hash), entry.length)
            }
        }

    }
}

internal class BoundedBytes(private val maximum: Int) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count < maximum) { "Restored compact record exceeds safe budget" }
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= maximum - count) { "Restored compact record exceeds safe budget" }
        super.write(bytes, offset, length)
    }
}
