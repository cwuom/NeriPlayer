package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.SequenceInputStream
import java.util.Collections
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyCodec
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLyricBodyCodecTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fixedPythonReferenceRetainsNumericWidthsAndRoleDeltas() {
        val bodies = listOf("[00:000.001]a(0002,03,0)b[0002,003]a".toByteArray(), ByteArray(0))
        val wire = encode(bodies)
        assertArrayEquals(hex("010514020300000102030301010402010102040301000011080100010001010104010301000104010305020161016202010306000001000000"), wire)
        assertBodies(bodies, decode(wire))
    }

    @Test fun exactTextAndUnicodeRoundTripWithoutNormalizingLineEndings() {
        val bodies = listOf("", "\u0000\r\n\n", "[00:000.0001]中文🙂\r\n[123:45:678]é\r",
            "(123,00004,00)逐字[123,00004]", "[999999999999999999:00.01]极限\n[00:00.00]回退",
            "[0000000000000000000123:45.67]不拆分", "1".repeat(5000), "非 LRC [bad] x").map { it.toByteArray() }
        val wire = encode(bodies)
        assertEquals(1, wire[0].toInt())
        assertBodies(bodies, decode(wire))
    }

    @Test fun arbitraryBytesEmptyLiteralsAndOversizedPalettesUseLosslessRawMode() {
        val widePalette = buildString {
            var codePoint = 0
            var count = 0
            while (count < 65_537) {
                if (codePoint !in 0xd800..0xdfff) { appendCodePoint(codePoint); count++ }
                codePoint++
            }
        }.toByteArray()
        for (bodies in listOf(emptyList(), listOf(ByteArray(0)), listOf("[00:00.00]".toByteArray()),
            listOf(byteArrayOf(0xc3.toByte(), 0x28, 0, 13, 10)), listOf(widePalette))) {
            val wire = encode(bodies)
            assertEquals(0, wire[0].toInt())
            assertBodies(bodies, decode(wire))
        }
    }

    @Test fun entryAndGroupLimitsApplyOnBothSides() {
        val maximum = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
        assertBodies(listOf(maximum, maximum), decode(encode(listOf(maximum, maximum))))
        assertThrows(IllegalArgumentException::class.java) { encode(listOf(maximum + 1)) }
        assertThrows(IllegalArgumentException::class.java) { encode(listOf(maximum, maximum, byteArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { encode(List(4097) { ByteArray(0) }) }
        assertThrows(IllegalArgumentException::class.java) { decode(frame(0, listOf(unsigned(4097), pack(List(8) { ByteArray(0) }), ByteArray(0)))) }
        val bodyLayout = unsigned(1) + unsigned(0) + unsigned(maximum.size + 1)
        assertThrows(IllegalArgumentException::class.java) { decode(frame(0, listOf(bodyLayout, pack(List(8) { ByteArray(0) }), maximum + 1))) }
    }

    @Test fun malformedUtf8FallsBackWhileRealReplacementAndSurrogateBoundariesRemainUnicode() {
        val invalid = listOf("c0af", "e080af", "f08080af", "eda080", "edbfbf", "f4908080", "80", "e282", "f09f99")
        for (value in invalid) {
            val body = hex(value)
            val wire = encode(listOf(body))
            assertEquals(0, wire[0].toInt())
            assertBodies(listOf(body), decode(wire))
        }
        val valid = listOf("efbfbd", "ed9fbf", "ee8080", "f48fbfbf", "00").map(::hex)
        val wire = encode(valid)
        assertEquals(1, wire[0].toInt())
        assertBodies(valid, decode(wire))
    }

    @Test fun malformedFramesPalettesAndExtraNumericBytesAreRejected() {
        val wire = encode(listOf("[00:00.00]x".toByteArray()))
        for (broken in listOf(wire.copyOf(wire.size - 1), wire + 0, wire.copyOf().also { it[0] = 2 },
            wire.copyOf().also { it[1] = 3 }, byteArrayOf(0, 3, 0x80.toByte()),
            byteArrayOf(0, 3, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f))) {
            assertThrows(IllegalArgumentException::class.java) { decode(broken) }
        }
        val validLayout = byteArrayOf(1, 1, 0, 0, 1, 2, 2, 2, 1)
        val columns = List(8) { if (it < 3) byteArrayOf(0) else ByteArray(0) }
        val validParts = listOf(validLayout, pack(columns), pack(listOf("x".toByteArray())), byteArrayOf(1, 1), byteArrayOf(0, 0))
        assertBodies(listOf("[00:00.00]x".toByteArray()), decode(frame(1, validParts)))
        val corruptParts = listOf(
            validParts.toMutableList().also { it[0] = validLayout.copyOf().also { layout -> layout[5] = 0 } },
            validParts.toMutableList().also { it[0] = validLayout.copyOf().also { layout -> layout[3] = 3 } },
            validParts.toMutableList().also { it[1] = pack(columns.toMutableList().also { c -> c[7] = byteArrayOf(0) }) },
            validParts.toMutableList().also { it[2] = pack(listOf(byteArrayOf(0xff.toByte()))) },
            validParts.toMutableList().also { it[2] = pack(listOf("xy".toByteArray())) },
            validParts.toMutableList().also { it[4] = byteArrayOf(1, 0) },
            validParts.toMutableList().also { it[3] = byteArrayOf(1, 2) }
        )
        for (parts in corruptParts) assertThrows(IllegalArgumentException::class.java) { decode(frame(1, parts)) }
    }

    @Test fun cancellationDeletesOnlyCreatedPartsAndDecodeStopsEarly() {
        val workspace = temporary.newFolder()
        val unrelated = File(workspace, "caller-owned").apply { writeText("keep") }
        val stopped = CancellationException("cancelled")
        assertThrows(CancellationException::class.java) {
            SyncLyricBodyCodec.encode(listOf("test".toByteArray()), workspace) {
                if (workspace.listFiles().orEmpty().any { it != unrelated }) throw stopped
            }
        }
        assertEquals(listOf(unrelated), workspace.listFiles().orEmpty().toList())
        val wire = encode(listOf("test".toByteArray()))
        assertThrows(CancellationException::class.java) {
            SyncLyricBodyCodec.decode(ByteArrayInputStream(wire), workspace) { throw stopped }
        }
        assertEquals("keep", unrelated.readText())
    }

    @Test fun canonicalIntegersCoverNineByteValuesAndRejectAmbiguousOrTruncatedForms() {
        for (number in listOf(0L, 127L, 128L, 999_999_999_999_999_999L, Long.MAX_VALUE)) {
            val output = ByteArrayOutputStream()
            SyncLyricBodyWire.writeUnsigned(output, number)
            val input = ByteArrayInputStream(output.toByteArray())
            assertEquals(number, SyncLyricBodyWire.readUnsigned(input))
            assertEquals(-1, input.read())
        }
        for (bytes in listOf(ByteArray(0), hex("80"), hex("8000"), ByteArray(9) { 0x80.toByte() })) {
            assertThrows(IllegalArgumentException::class.java) { SyncLyricBodyWire.readUnsigned(ByteArrayInputStream(bytes)) }
        }
        assertThrows(IllegalArgumentException::class.java) { SyncLyricBodyWire.writeUnsigned(ByteArrayOutputStream(), -1) }
        val workspace = File(temporary.root, "missing")
        assertThrows(IllegalArgumentException::class.java) { SyncLyricBodyCodec.encode(listOf(ByteArray(0)), workspace) {} }
        assertThrows(IllegalArgumentException::class.java) { SyncLyricBodyCodec.decode(ByteArrayInputStream(byteArrayOf()), workspace) {} }
    }

    @Test fun timestampSyntaxValuesAndWidthsAreCheckedBeforeRestoration() {
        val layout = byteArrayOf(1, 1, 0, 0, 1, 2, 2, 2, 0)
        val columns = List(8) { if (it < 3) byteArrayOf(0) else ByteArray(0) }
        val parts = listOf(layout, pack(columns), ByteArray(0))
        assertBodies(listOf("[00:00.00]".toByteArray()), decode(frame(0, parts)))
        val broken = listOf(
            parts.toMutableList().also { it[0] = layout.copyOf().also { b -> b[4] = 2 } },
            parts.toMutableList().also { it[0] = layout.copyOf().also { b -> b[5] = 19 } },
            parts.toMutableList().also { it[1] = pack(columns.toMutableList().also { c -> c[0] = unsigned(1) }) },
            parts.toMutableList().also { it[1] = pack(columns.toMutableList().also { c -> c[1] = unsigned(1_000_000_000_000_000_000L) }) },
            parts.toMutableList().also {
                it[0] = layout.copyOf().also { b -> b[5] = 1 }
                it[1] = pack(columns.toMutableList().also { c -> c[0] = unsigned(20) })
            },
            parts.toMutableList().also { it[1] = pack(columns.toMutableList().also { c -> c[0] = ByteArray(0) }) },
            parts.toMutableList().also { it[0] = layout + 0 },
            parts.toMutableList().also { it[1] = pack(columns) + 0 },
            parts.toMutableList().also { it[2] = byteArrayOf(0) }
        )
        for (value in broken) assertThrows(IllegalArgumentException::class.java) { decode(frame(0, value)) }
        val atLimit = "[999999999999999999:999999999999999999.999999999999999999]" +
            "[000000000000000000:00:00][999999999999999999,00]"
        assertBodies(listOf(atLimit.toByteArray()), decode(encode(listOf(atLimit.toByteArray()))))
    }

    @Test fun paletteAndCharacterFramesRejectEmptyDuplicateOrUnusedContent() {
        val parts = listOf(byteArrayOf(1, 0, 1), pack(List(8) { ByteArray(0) }), pack(listOf(byteArrayOf('x'.code.toByte()))), byteArrayOf(1, 1), byteArrayOf(0, 0))
        val invalidPalettes = listOf(unsigned(0), pack(listOf(ByteArray(0))), pack(listOf("x".toByteArray(), "x".toByteArray())), parts[2] + 0)
        for (palette in invalidPalettes) {
            val invalid = parts.toMutableList().also { it[2] = palette }
            assertThrows(IllegalArgumentException::class.java) { decode(frame(1, invalid)) }
        }
        for (lengths in listOf(byteArrayOf(0, 1), byteArrayOf(1, 1, 0), unsigned(1) + unsigned(4 * 1024 * 1024 + 1))) {
            val invalid = parts.toMutableList().also { it[3] = lengths }
            assertThrows(IllegalArgumentException::class.java) { decode(frame(1, invalid)) }
        }
        val zeroCharacters = parts.toMutableList().also {
            it[0] = byteArrayOf(1, 0, 0)
            it[3] = byteArrayOf(1, 0)
            it[4] = ByteArray(0)
        }
        assertBodies(listOf(ByteArray(0)), decode(frame(1, zeroCharacters)))
    }

    @Test fun restoredBudgetsIncludeTimestampsAndExpandedUnicode() {
        val maximum = 2 * 1024 * 1024
        val columns = pack(List(8) { if (it < 3) byteArrayOf(0) else ByteArray(0) })
        val syntax = byteArrayOf(0, 1, 2, 2, 2)
        val tokenOverflow = unsigned(1) + unsigned(1) + unsigned(maximum - 1) + syntax + unsigned(0)
        assertFailure("restored body", frame(0, listOf(tokenOverflow, columns, ByteArray(maximum - 1))))
        val literalOverflow = unsigned(1) + unsigned(1) + unsigned(0) + syntax + unsigned(maximum)
        assertFailure("literal body", frame(0, listOf(literalOverflow, columns, ByteArray(maximum))))
        val truncatedLiteral = unsigned(1) + unsigned(0) + unsigned(1)
        assertFailure("Truncated lyric literal", frame(0, listOf(truncatedLiteral, pack(List(8) { ByteArray(0) }), ByteArray(0))))
        val restoredGroup = unsigned(3) + unsigned(0) + unsigned(maximum) + unsigned(0) + unsigned(maximum) +
            unsigned(1) + unsigned(0) + syntax + unsigned(0)
        assertFailure("restored group", frame(0, listOf(restoredGroup, columns, ByteArray(2 * maximum))))
        val characters = 1024 * 1024 + 1
        val unicode = listOf(byteArrayOf(0), pack(List(8) { ByteArray(0) }), pack(listOf("🙂".toByteArray())), unsigned(1) + unsigned(characters), ByteArray(characters * 2))
        assertFailure("literals exceed", frame(1, unicode))
        assertFailure("literal group", frame(0, listOf(byteArrayOf(0), pack(List(8) { ByteArray(0) }), ByteArray(4 * 1024 * 1024 + 1))))
    }

    @Test fun encodedPartBudgetsRejectOversizedDeclarationsAndCumulativeSize() {
        val large = ByteArray(8 * 1024 * 1024)
        val streams = listOf(ByteArrayInputStream(unsigned(3) + unsigned(large.size)), ByteArrayInputStream(large),
            ByteArrayInputStream(unsigned(large.size)), ByteArrayInputStream(large), ByteArrayInputStream(unsigned(5 * 1024 * 1024)))
        val input = SequenceInputStream(Collections.enumeration(streams))
        val failure = assertThrows(IllegalArgumentException::class.java) { SyncLyricBodyWire.readParts(input, 3) {} }
        assertTrue(failure.message.orEmpty().contains("encoded group"))
        val declared = unsigned(3) + unsigned(8 * 1024 * 1024 + 512 * 1024 + 1)
        assertThrows(IllegalArgumentException::class.java) { SyncLyricBodyWire.readParts(ByteArrayInputStream(declared), 3) {} }
    }

    private fun encode(bodies: List<ByteArray>): ByteArray {
        val parts = SyncLyricBodyCodec.encode(bodies, temporary.newFolder()) {}
        assertTrue(parts.size == 3 || parts.size == 4)
        return ByteArrayOutputStream().also { output -> parts.forEach { file -> file.inputStream().use { it.copyTo(output) } } }.toByteArray()
    }

    private fun decode(wire: ByteArray) = SyncLyricBodyCodec.decode(ByteArrayInputStream(wire), temporary.newFolder()) {}

    private fun assertBodies(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertArrayEquals(left, right) }
    }

    private fun assertFailure(message: String, wire: ByteArray) {
        val failure = assertThrows(IllegalArgumentException::class.java) { decode(wire) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(message))
    }

    private fun frame(mode: Int, parts: List<ByteArray>) = byteArrayOf(mode.toByte()) + pack(parts)
    private fun pack(parts: List<ByteArray>) = unsigned(parts.size) + parts.fold(ByteArray(0)) { result, part -> result + unsigned(part.size) + part }
    private fun unsigned(input: Int): ByteArray = unsigned(input.toLong())
    private fun unsigned(input: Long): ByteArray {
        var value = input
        val output = ByteArrayOutputStream()
        while (value > 127) { output.write((value.toInt() and 127) or 128); value = value ushr 7 }
        output.write(value.toInt())
        return output.toByteArray()
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
