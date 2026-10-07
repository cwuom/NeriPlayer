package moe.ouom.neriplayer.util.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.DisplayMetrics
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class OnePlusDensityContextPassthroughTest {

    @Test
    fun `a plain context without the OnePlus build identity is returned unchanged`() {
        val context = contextWithDensity(densityDpi = 640)

        assertSame(context, applyOnePlusHighDensityDisplayCorrection(context))
        verify(context, never()).createConfigurationContext(any())
    }

    @Test
    fun `a wrapped context is inspected through its base chain and returned unchanged`() {
        val base = contextWithDensity(densityDpi = 640)
        val baseResources = base.resources
        val wrapper = mock(ContextWrapper::class.java).also { wrapper ->
            `when`(wrapper.baseContext).thenReturn(base)
            `when`(wrapper.resources).thenReturn(baseResources)
        }

        assertSame(wrapper, applyOnePlusHighDensityDisplayCorrection(wrapper))
        verify(wrapper).baseContext
        verify(wrapper, never()).createConfigurationContext(any())
    }

    private fun contextWithDensity(densityDpi: Int): Context {
        val metrics = DisplayMetrics().apply { this.densityDpi = densityDpi }
        val resources = mock(Resources::class.java).also { `when`(it.displayMetrics).thenReturn(metrics) }
        return mock(Context::class.java).also { `when`(it.resources).thenReturn(resources) }
    }
}
