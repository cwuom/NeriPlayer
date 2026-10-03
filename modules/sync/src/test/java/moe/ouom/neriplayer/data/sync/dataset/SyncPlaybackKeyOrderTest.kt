package moe.ouom.neriplayer.data.sync.dataset

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class SyncPlaybackKeyOrderTest {
    @Test fun unicodeBoundariesAndMalformedSurrogatesMatchUnsignedUtf8Order() {
        val keys = listOf(
            "", "\u0000", "?", "??", "a", "a\u0000", "a?", "a\u007f", "\u007f", "\u0080", "\u07ff", "\u0800",
            "\ud7ff", "\ue000", "\uffff", "\ud800\udc00", "\udbff\udfff", "😀", "中", "track|中|😀",
            "\ud800", "\udbff", "\udc00", "\udfff", "\ud800\ud800", "\udc00\udc00", "\udc00\ud800",
            "\ud800?", "?\udfff", "\ud800a", "a\ud800", "\udc00\ud800\udc00", "\ud800\ud800\udc00",
            "\ud800\udc00\udc00", "a\ud800\ud800", "a??", "prefix-😀", "prefix-\ue000"
        )
        for (left in keys) for (right in keys) assertSameUtf8Order(left, right)
        assertEquals(0, SyncPlaybackKeyOrder.compare("\ud800\ud800", "??"))
        assertEquals(0, SyncPlaybackKeyOrder.compare("\udc00\ud800", "??"))
        assertEquals(0, SyncPlaybackKeyOrder.compare("\udc00\ud800\udc00", "?\ud800\udc00"))
    }

    @Test fun fixedSeedMixedUtf16AndLongCommonPrefixesMatchUtf8ByteSorting() {
        val random = Random(0x4e455249L)
        repeat(20_000) {
            val left = key(random)
            val right = key(random)
            assertSameUtf8Order(left, right)
            assertSameUtf8Order(left, left + right)
            val prefix = key(random).repeat(4)
            assertSameUtf8Order(prefix + left, prefix + right)
        }
        val keys = List(2_000) { key(random) }
        val expected = keys.sortedWith { a, b -> utf8Order(a, b) }
        assertEquals(expected, keys.sortedWith(SyncPlaybackKeyOrder))
    }

    private fun key(random: Random): String = buildString {
        repeat(random.nextInt(48)) {
            when (random.nextInt(7)) {
                0 -> append(random.nextInt(128).toChar())
                1 -> append((0x80 + random.nextInt(0x780)).toChar())
                2 -> append((0x800 + random.nextInt(0xd000)).toChar())
                3 -> append((0xe000 + random.nextInt(0x2000)).toChar())
                4 -> append(Character.toChars(0x10000 + random.nextInt(0x100000)))
                5 -> append((0xd800 + random.nextInt(0x400)).toChar())
                else -> append((0xdc00 + random.nextInt(0x400)).toChar())
            }
        }
    }

    private fun assertSameUtf8Order(left: String, right: String) {
        val expected = sign(utf8Order(left, right))
        assertEquals(expected, sign(SyncPlaybackKeyOrder.compare(left, right)))
        assertEquals(-expected, sign(SyncPlaybackKeyOrder.compare(right, left)))
    }

    private fun utf8Order(left: String, right: String): Int =
        SyncPlaybackKeyOrder.compareBytes(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private fun sign(value: Int): Int = value.compareTo(0)
}
