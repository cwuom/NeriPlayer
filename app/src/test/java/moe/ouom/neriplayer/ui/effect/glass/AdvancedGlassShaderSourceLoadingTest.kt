package moe.ouom.neriplayer.ui.effect.glass

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AdvancedGlassShaderSourceLoadingTest {
    private val assets = mock(AssetManager::class.java)

    @Test
    fun forwardLoadPreservesContentAndDoesNotOpenInverse() {
        val input = TrackedInputStream("forward source\n")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)).thenReturn(input)
        val source = AdvancedGlassShaderSource(assets)

        val first = source.load()

        assertEquals("forward source\n", first)
        assertSame(first, source.load())
        assertTrue(input.closed)
        verify(assets, times(1)).open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)
        verify(assets, never()).open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun inverseLoadUsesItsOwnAssetAndDoesNotOpenForward() {
        val input = TrackedInputStream("inverse source\n")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("forward source\n"))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)).thenReturn(input)
        val source = AdvancedGlassShaderSource(assets)

        val first = source.loadInverse()

        assertEquals("inverse source\n", first)
        assertSame(first, source.loadInverse())
        assertTrue(input.closed)
        verify(assets, times(1)).open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)
        verify(assets, never()).open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun interleavedLoadsCacheEachAssetIndependently() {
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("forward\n"))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("inverse\n"))
        val source = AdvancedGlassShaderSource(assets)

        val inverse = source.loadInverse()
        val forward = source.load()

        assertEquals("forward\n", forward)
        assertEquals("inverse\n", inverse)
        assertSame(inverse, source.loadInverse())
        assertSame(forward, source.load())
        verify(assets, times(1)).open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)
        verify(assets, times(1)).open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun missingInverseReportsItsPathAndCauseWithoutPoisoningForwardCache() {
        val failure = IOException("inverse missing")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("forward\n"))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)).thenThrow(failure)
        val source = AdvancedGlassShaderSource(assets)
        val forward = source.load()

        val error = assertThrows(IllegalStateException::class.java) { source.loadInverse() }

        assertTrue(error.message.orEmpty().contains(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
        assertSame(failure, error.cause)
        assertSame(forward, source.load())
        verify(assets, times(1)).open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun blankInverseReportsItsOwnPathAndClosesItsInput() {
        val input = TrackedInputStream(" \n\t")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("forward source\n"))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)).thenReturn(input)
        val source = AdvancedGlassShaderSource(assets)

        val error = assertThrows(IllegalStateException::class.java) { source.loadInverse() }

        assertTrue(error.message.orEmpty().contains(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
        assertTrue(input.closed)
        verify(assets, never()).open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun failedInverseLazyReadRetriesAndThenCachesSuccess() {
        val failure = IOException("temporary read failure")
        val recoveredInput = TrackedInputStream("recovered inverse\n")
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("forward source\n"))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH))
            .thenThrow(failure)
            .thenReturn(recoveredInput)
        val source = AdvancedGlassShaderSource(assets)
        assertThrows(IllegalStateException::class.java) { source.loadInverse() }

        val recovered = source.loadInverse()

        assertEquals("recovered inverse\n", recovered)
        assertSame(recovered, source.loadInverse())
        assertTrue(recoveredInput.closed)
        verify(assets, times(2)).open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)
    }

    @Test
    fun inverseReadFailureClosesInputAndPreservesOriginalCause() {
        val failure = IOException("stream failed")
        val input = FailingInputStream(failure)
        `when`(assets.open(ADVANCED_GLASS_REGION_MASK_SHADER_ASSET_PATH))
            .thenReturn(TrackedInputStream("forward source\n"))
        `when`(assets.open(ADVANCED_GLASS_REGION_INVERSE_MASK_SHADER_ASSET_PATH)).thenReturn(input)
        val source = AdvancedGlassShaderSource(assets)

        val error = assertThrows(IllegalStateException::class.java) { source.loadInverse() }

        assertSame(failure, error.cause)
        assertTrue(input.closed)
    }

    private class TrackedInputStream(value: String) : ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FailingInputStream(private val failure: IOException) : InputStream() {
        var closed = false
            private set

        override fun read(): Int = throw failure

        override fun close() {
            closed = true
        }
    }
}
