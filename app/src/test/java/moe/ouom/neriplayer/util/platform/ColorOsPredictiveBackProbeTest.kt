package moe.ouom.neriplayer.util.platform

import android.app.Application
import java.io.IOException
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mockConstruction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ColorOsPredictiveBackProbeTest {
    @Test
    fun `temporary property read failure remains unknown instead of unsupported`() {
        ShadowBuild.setManufacturer("OPPO")
        ShadowBuild.setBrand("OPPO")
        mockConstruction(ProcessBuilder::class.java) { builder, _ ->
            `when`(builder.start()).thenThrow(IOException("temporary getprop failure"))
        }.use {
            val readSupport = Class.forName(
                "moe.ouom.neriplayer.util.platform.ColorOsPredictiveBackSupportKt"
            ).getDeclaredMethod("readColorOsPredictiveBackSupport").apply { isAccessible = true }

            assertNull(readSupport.invoke(null))
        }
    }
}
