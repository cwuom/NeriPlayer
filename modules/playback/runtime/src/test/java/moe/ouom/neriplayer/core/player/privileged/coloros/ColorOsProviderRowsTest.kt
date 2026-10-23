package moe.ouom.neriplayer.core.player.privileged.coloros

import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorOsProviderRowsTest {
    @Test fun `only empty cursor represents absent configuration`() {
        assertNull(ColorOsProviderRows.read(MatrixCursor(arrayOf("version", "xml"))))
        assertThrows(ColorOsConfigException::class.java) { ColorOsProviderRows.read(null) }
    }

    @Test fun `typed cursor keeps optional schema and closes cursor`() {
        val cursor = MatrixCursor(arrayOf("version", "xml", "md5", "isOpen"))
        cursor.addRow(arrayOf<Any?>(123L, "<config/>", null, 1))
        assertEquals(ColorOsRusRow("123", "<config/>", true, null, true, "1"), ColorOsProviderRows.read(cursor))
        assertTrue(cursor.isClosed)
    }

    @Test fun `duplicate rows fail closed and release cursor`() {
        val cursor = MatrixCursor(arrayOf("version", "xml"))
        repeat(2) { cursor.addRow(arrayOf("1", "<x/>")) }
        val error = assertThrows(ColorOsConfigException::class.java) { ColorOsProviderRows.read(cursor) }
        assertEquals(ColorOsConfigStatus.CONFLICT, error.status)
        assertTrue(cursor.isClosed)
    }

    @Test fun `missing required column and wrong xml type are structured errors`() {
        val missing = MatrixCursor(arrayOf("version"))
        missing.addRow(arrayOf("1"))
        assertThrows(ColorOsConfigException::class.java) { ColorOsProviderRows.read(missing) }
        val blob = MatrixCursor(arrayOf("version", "xml"))
        blob.addRow(arrayOf<Any?>("1", byteArrayOf(1)))
        assertThrows(ColorOsConfigException::class.java) { ColorOsProviderRows.read(blob) }
    }

    @Test fun `CAS keeps xml as parameter and includes optional column baseline`() {
        val row = ColorOsRusRow("9", "<x a=' OR 1=1'/>", true, null, true, "1")
        val (selection, args) = ColorOsProviderRows.selection(row)
        assertEquals("filtername = ? AND version = ? AND xml = ? AND md5 IS NULL AND isOpen = ?", selection)
        assertEquals(listOf("app_systemui_oplus_media_controller_config", "9", row.xml, "1"), args.toList())
    }
}
