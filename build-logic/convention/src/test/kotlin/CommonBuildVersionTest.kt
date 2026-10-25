import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonBuildVersionTest {
    @Test
    fun `fixed build timestamp produces the same version code across invocations`() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals(26102112, Common.getBuildVersionCode("1792585200000"))
            assertEquals(26102112, Common.getBuildVersionCode("1792585200000"))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `fixed build timestamp keeps the existing default time zone rule`() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"))
            assertEquals(26102120, Common.getBuildVersionCode("1792585200000"))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `missing build timestamp retains the current date version code`() {
        val formatter = SimpleDateFormat("yyMMddHH", Locale.ENGLISH)
        val before = formatter.format(Date()).toInt()
        val actual = Common.getBuildVersionCode()
        val after = formatter.format(Date()).toInt()
        assertTrue(actual == before || actual == after)
    }

    @Test
    fun `rejects nonpositive and malformed build timestamps`() {
        listOf("0", "-1", "unknown", "", "9223372036854775808").forEach { timestamp ->
            assertThrows(IllegalArgumentException::class.java) {
                Common.getBuildVersionCode(timestamp)
            }
        }
    }
}
