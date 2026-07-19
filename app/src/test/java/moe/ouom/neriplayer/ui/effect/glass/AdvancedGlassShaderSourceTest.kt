package moe.ouom.neriplayer.ui.effect.glass

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassShaderSourceTest {
    @Test
    fun readsShaderSourceWithoutChangingContent() {
        val source = "uniform shader child;\nhalf4 main(float2 p) { return child.eval(p); }\n"

        val loaded = readShaderSource("shaders/test.agsl") {
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))
        }

        assertEquals(source, loaded)
    }

    @Test
    fun reportsAssetPathWhenReadingFails() {
        val failure = IOException("missing")

        val error = assertThrows(IllegalStateException::class.java) {
            readShaderSource("shaders/missing.agsl") { throw failure }
        }

        assertTrue(error.message.orEmpty().contains("shaders/missing.agsl"))
        assertSame(failure, error.cause)
    }

    @Test
    fun rejectsBlankShaderSource() {
        val error = assertThrows(IllegalStateException::class.java) {
            readShaderSource("shaders/blank.agsl") {
                ByteArrayInputStream(" \n\t".toByteArray(Charsets.UTF_8))
            }
        }

        assertTrue(error.message.orEmpty().contains("shaders/blank.agsl"))
    }
}
