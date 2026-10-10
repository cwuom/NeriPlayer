package moe.ouom.neriplayer.ui.theme.background

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito.mock

class BlurTransformationEqualityTest {

    private val context = mock(Context::class.java)

    @Test
    fun `transformations with the same radius are equal across contexts`() {
        val first = BlurTransformation(context, radius = 18f)
        val second = BlurTransformation(mock(Context::class.java), radius = 18f)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.cacheKey, second.cacheKey)
    }

    @Test
    fun `transformations with different radii are not equal`() {
        assertNotEquals(BlurTransformation(context, radius = 18f), BlurTransformation(context, radius = 18.5f))
    }

    @Test
    fun `radius equality is bitwise so signed zeros differ and NaN matches itself`() {
        assertNotEquals(BlurTransformation(context, radius = 0f), BlurTransformation(context, radius = -0f))
        assertEquals(BlurTransformation(context, radius = Float.NaN), BlurTransformation(context, radius = Float.NaN))
    }

    @Test
    fun `transformations never equal other values`() {
        val transformation = BlurTransformation(context, radius = 18f)

        assertNotEquals(transformation, 18f)
        assertNotEquals(transformation, null)
    }
}
