package moe.ouom.neriplayer.core.player.privileged.coloros

import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorOsAndroidProviderTest {
    @Test fun `token is released when cursor decoding rejects duplicate rows`() {
        val session = Session().apply { duplicate = true }
        val error = assertThrows(ColorOsConfigException::class.java) { ColorOsAndroidProvider(0) { session }.read() }
        assertEquals(ColorOsConfigStatus.CONFLICT, error.status)
        assertTrue(session.closed)
    }

    @Test fun `release failure does not mask provider permission denial`() {
        val session = Session().apply { denied = true; releaseFails = true }
        val error = assertThrows(SecurityException::class.java) { ColorOsAndroidProvider(0) { session }.read() }
        assertEquals(1, error.suppressed.size)
        assertTrue(session.closed)
    }

    @Test fun `CAS refuses changed baseline without provider update`() {
        val session = Session()
        val provider = ColorOsAndroidProvider(0) { session }
        assertEquals(0, provider.compareAndSet(ColorOsRusRow("2", "<x/>"), ColorOsRusRow("3", "<x/>")))
        assertEquals(0, session.updated)
        assertTrue(session.closed)
    }

    @Test fun `CAS returns actual count and writes only known schema`() {
        val session = Session()
        val provider = ColorOsAndroidProvider(0) { session }
        assertEquals(1, provider.compareAndSet(ColorOsRusRow("1", "<x/>"), ColorOsRusRow("2", "<y/>")))
        assertEquals(setOf("version", "xml"), session.values!!.keySet())
        assertEquals("filtername = ? AND version = ? AND xml = ?", session.selection)
        assertEquals(listOf(ColorOsTransportProtocol.FILTER, "1", "<x/>"), session.args!!.toList())
        assertTrue(session.closed)
    }

    @Test fun `release failure after a submitted update reports unknown write outcome`() {
        val session = Session().apply { releaseFails = true }
        val provider = ColorOsAndroidProvider(0) { session }
        val error = assertThrows(ColorOsConfigException::class.java) {
            provider.compareAndSet(ColorOsRusRow("1", "<x/>"), ColorOsRusRow("2", "<y/>"))
        }
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, error.status)
        assertEquals(1, session.updated)
        assertTrue(session.closed)
    }

    private class Session : ColorOsExternalProviderSession {
        var duplicate = false
        var denied = false
        var releaseFails = false
        var closed = false
        var updated = 0
        var values: ContentValues? = null
        var selection: String? = null
        var args: Array<String>? = null
        override fun query(): Cursor {
            if (denied) throw SecurityException("provider")
            return MatrixCursor(arrayOf("version", "xml")).apply {
                addRow(arrayOf("1", "<x/>"))
                if (duplicate) addRow(arrayOf("1", "<x/>"))
            }
        }
        override fun update(values: ContentValues, selection: String, args: Array<String>): Int {
            updated++
            this.values = ContentValues(values)
            this.selection = selection
            this.args = args
            return 1
        }
        override fun close() {
            closed = true
            if (releaseFails) throw IllegalStateException("release")
        }
    }
}
