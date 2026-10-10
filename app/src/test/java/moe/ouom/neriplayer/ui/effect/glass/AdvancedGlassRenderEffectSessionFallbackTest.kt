package moe.ouom.neriplayer.ui.effect.glass

import android.content.res.AssetManager
import android.os.Build
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class AdvancedGlassRenderEffectSessionFallbackTest {

    private val assets = mock(AssetManager::class.java)
    private val region = AdvancedGlassRenderRegion(
        left = 0f,
        top = 0f,
        right = 120f,
        bottom = 48f,
        cornerRadiiPx = AdvancedGlassCornerRadii.Zero
    )

    @Test
    fun `a runtime below the backend sdk never produces an effect even for supported devices`() {
        val session = createAdvancedGlassRenderEffectSession(
            shaderSource = AdvancedGlassShaderSource(assets),
            sdkInt = Build.VERSION_CODES.TIRAMISU
        )

        assertNull(session.update(radiusPx = 24f, regions = listOf(region)))
        assertNull(session.update(radiusPx = 8f, regions = listOf(region, region.copy(left = 200f, right = 260f))))
        verify(assets, never()).open(anyString())
    }

    @Test
    fun `an unsupported device sdk falls back without loading the mask shader`() {
        val session = createAdvancedGlassRenderEffectSession(
            shaderSource = AdvancedGlassShaderSource(assets),
            sdkInt = Build.VERSION_CODES.S
        )

        assertNull(session.update(radiusPx = 24f, regions = listOf(region)))
        verify(assets, never()).open(anyString())
    }
}
