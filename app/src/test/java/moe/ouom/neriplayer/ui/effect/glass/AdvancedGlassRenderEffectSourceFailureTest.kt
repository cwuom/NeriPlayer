package moe.ouom.neriplayer.ui.effect.glass

import android.app.Application
import android.content.res.AssetManager
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdvancedGlassRenderEffectSourceFailureTest {
    private val assets = mock(AssetManager::class.java)
    private val region = AdvancedGlassRenderRegion(0f, 0f, 120f, 48f, AdvancedGlassCornerRadii.Zero)

    @Test
    fun missingForwardStillFailsBeforeOpeningInverse() {
        val failure = IOException("forward missing")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)).thenThrow(failure)

        val error = updateFailure()

        assertTrue(error.message.orEmpty().contains(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
        assertSame(failure, error.cause)
        verify(assets, never()).open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun blankForwardStillFailsBeforeOpeningInverse() {
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(ByteArrayInputStream(" \n\t".toByteArray()))

        val error = updateFailure()

        assertTrue(error.message.orEmpty().contains(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
        verify(assets, never()).open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun missingInverseFailsInsteadOfConstructingTheBackendWithAnOldSource() {
        val failure = IOException("inverse missing")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(ByteArrayInputStream("nonblank forward input".toByteArray()))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)).thenThrow(failure)

        val error = updateFailure()

        assertTrue(error.message.orEmpty().contains(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
        assertSame(failure, error.cause)
    }

    @Test
    fun blankInverseFailsBeforeAnyRuntimeShaderConstruction() {
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(ByteArrayInputStream("nonblank forward input".toByteArray()))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
            .thenReturn(ByteArrayInputStream(" \n\t".toByteArray()))

        val error = updateFailure()

        assertTrue(error.message.orEmpty().contains(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
    }

    private fun updateFailure(): IllegalStateException {
        val session = createAdvancedGlassRenderEffectSession(AdvancedGlassShaderSource(assets), Build.VERSION.SDK_INT)
        return assertThrows(IllegalStateException::class.java) { session.update(24f, listOf(region)) }
    }
}
