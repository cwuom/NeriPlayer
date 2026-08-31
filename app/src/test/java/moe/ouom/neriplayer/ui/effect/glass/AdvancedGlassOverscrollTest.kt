package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassOverscrollTest {
    @Test
    fun dragGetsProgressivelyMoreResistant() {
        val resistanceScale = 300f
        val first = dampedAdvancedGlassOverscrollOffset(75f, resistanceScale)
        val second = dampedAdvancedGlassOverscrollOffset(150f, resistanceScale)
        val third = dampedAdvancedGlassOverscrollOffset(225f, resistanceScale)

        assertTrue(first > 0f)
        assertTrue(second - first < first)
        assertTrue(third - second < second - first)
    }

    @Test
    fun dragIsSymmetricAndContinuesWithIncreasingResistance() {
        val resistanceScale = 100f

        val first = dampedAdvancedGlassOverscrollOffset(100f, resistanceScale)
        val second = dampedAdvancedGlassOverscrollOffset(200f, resistanceScale)
        val third = dampedAdvancedGlassOverscrollOffset(300f, resistanceScale)

        assertEquals(
            maxAdvancedGlassOverscrollOffset(resistanceScale) *
                (1f - kotlin.math.exp(-1f)),
            first,
            0.001f
        )
        assertEquals(-third, dampedAdvancedGlassOverscrollOffset(-300f, resistanceScale), 0.001f)
        assertTrue(third > second)
        assertTrue(third - second < second - first)
    }

    @Test
    fun dragDisplacementIsCappedWhileRemainingReversible() {
        val resistanceScale = 100f
        val maxOffset = maxAdvancedGlassOverscrollOffset(resistanceScale)
        val maxRawDrag = maxAdvancedGlassOverscrollRawDrag(resistanceScale)
        val cappedOffset = dampedAdvancedGlassOverscrollOffset(10_000f, resistanceScale)
        val reversibleOffset = dampedAdvancedGlassOverscrollOffset(400f, resistanceScale)
        val saturatedRawDrag = boundedAdvancedGlassOverscrollRawDrag(
            rawDrag = 0f,
            delta = 10_000f,
            resistanceScale = resistanceScale
        )
        val reversedRawDrag = boundedAdvancedGlassOverscrollRawDrag(
            rawDrag = saturatedRawDrag,
            delta = -100f,
            resistanceScale = resistanceScale
        )

        assertEquals(maxOffset, cappedOffset, 0.001f)
        assertEquals(maxRawDrag, saturatedRawDrag, 0.001f)
        assertEquals(maxRawDrag - 100f, reversedRawDrag, 0.001f)
        assertTrue(dampedAdvancedGlassOverscrollOffset(reversedRawDrag, resistanceScale) < cappedOffset)
        assertTrue(reversibleOffset < maxOffset)
        assertTrue(reversibleOffset > maxOffset * 0.98f)
        assertEquals(
            400f,
            restoredAdvancedGlassOverscrollDrag(reversibleOffset, resistanceScale),
            0.1f
        )
    }

    @Test
    fun animatedOffsetRestoresEquivalentRawDrag() {
        val resistanceScale = 100f
        listOf(-300f, -150f, -30f, 30f, 150f, 300f).forEach { rawDrag ->
            val offset = dampedAdvancedGlassOverscrollOffset(rawDrag, resistanceScale)
            val restored = restoredAdvancedGlassOverscrollDrag(offset, resistanceScale)

            assertEquals(rawDrag, restored, 0.01f)
        }
    }

    @Test
    fun positiveOverscrollFillsTheExposedTopEdgeUsingRoundedTranslation() {
        val fill = resolveAdvancedGlassOverscrollEdgeFill(
            offsetY = 47.6f,
            viewportHeight = 320f
        )

        val resolvedFill = requireNotNull(fill)
        assertEquals(0f, resolvedFill.top, 0.001f)
        assertEquals(48f, resolvedFill.height, 0.001f)
    }

    @Test
    fun negativeOverscrollDoesNotFillTheTopEdge() {
        assertNull(
            resolveAdvancedGlassOverscrollEdgeFill(
                offsetY = -48f,
                viewportHeight = 320f
            )
        )
    }

    @Test
    fun oversizedPositiveOverscrollIsClampedToTheViewport() {
        val fill = resolveAdvancedGlassOverscrollEdgeFill(
            offsetY = 500f,
            viewportHeight = 320f
        )

        assertEquals(320f, requireNotNull(fill).height, 0.001f)
    }

    @Test
    fun zeroOverscrollDoesNotDrawAnEdgeFill() {
        assertNull(
            resolveAdvancedGlassOverscrollEdgeFill(
                offsetY = 0f,
                viewportHeight = 320f
            )
        )
    }

    @Test
    fun largerFingerDisplacementGetsASlowerReturnPeriod() {
        val resistanceScale = 100f
        val shortDrag = advancedGlassOverscrollReturnPeriodSeconds(10f, resistanceScale)
        val longDrag = advancedGlassOverscrollReturnPeriodSeconds(60f, resistanceScale)

        assertTrue(longDrag > shortDrag)
        assertTrue(longDrag <= 0.62f)
    }

    @Test
    fun flingWaitsForTheListBeforeStartingTheReturnSpring() = runBlocking {
        val events = mutableListOf<String>()
        var receivedVelocity = Velocity.Zero
        var returnVelocity = Float.NaN

        runAdvancedGlassOverscrollFling(
            velocity = Velocity(7f, -1_200f),
            offsetY = 48f,
            performFling = { incoming ->
                events += "perform-start"
                receivedVelocity = incoming
                yield()
                events += "perform-end"
                Velocity(2f, -300f)
            },
            startReturnAnimation = { velocity ->
                events += "return"
                returnVelocity = velocity
            }
        )

        assertEquals(
            listOf("perform-start", "perform-end", "return"),
            events
        )
        assertEquals(
            resolveAdvancedGlassOverscrollFlingVelocity(
                velocity = Velocity(7f, -1_200f),
                offsetY = 48f
            ),
            receivedVelocity
        )
        assertEquals(
            (receivedVelocity.y - (-300f)) / 1.53333f,
            returnVelocity,
            0.01f
        )
    }

    @Test
    fun activeOverscrollKeepsTheListFlingDirectionSafeForBothEdges() = runBlocking {
        listOf(
            48f to -1_000f,
            -48f to 1_000f
        ).forEach { (offset, inputVelocity) ->
            var receivedVelocity = Velocity.Zero
            var calls = 0

            runAdvancedGlassOverscrollFling(
                velocity = Velocity.Zero.copy(y = inputVelocity),
                offsetY = offset,
                performFling = { incoming ->
                    calls += 1
                    receivedVelocity = incoming
                    Velocity.Zero
                },
                startReturnAnimation = {}
            )

            assertEquals(1, calls)
            assertTrue(receivedVelocity.y * offset < 0f)
            assertTrue(kotlin.math.abs(receivedVelocity.y) < kotlin.math.abs(inputVelocity))
        }
    }

    @Test
    fun activeOverscrollDoesNotPassTowardEdgeVelocityToTheList() = runBlocking {
        listOf(
            48f to 1_000f,
            -48f to -1_000f
        ).forEach { (offset, inputVelocity) ->
            var receivedVelocity = Velocity.Zero

            runAdvancedGlassOverscrollFling(
                velocity = Velocity.Zero.copy(y = inputVelocity),
                offsetY = offset,
                performFling = { incoming ->
                    receivedVelocity = incoming
                    Velocity.Zero
                },
                startReturnAnimation = {}
            )

            assertEquals(0f, receivedVelocity.y, 0.001f)
        }
    }

    @Test
    fun returnSpringReceivesExactlyTheUnconsumedVelocity() = runBlocking {
        val input = Velocity(11f, 900f)
        val consumed = Velocity(4f, 250f)
        var receivedVelocity = Velocity.Zero
        var returnVelocity = Float.NaN

        runAdvancedGlassOverscrollFling(
            velocity = input,
            offsetY = 0f,
            performFling = { incoming ->
                receivedVelocity = incoming
                consumed
            },
            startReturnAnimation = { velocity -> returnVelocity = velocity }
        )

        assertEquals(input, receivedVelocity)
        assertEquals((input.y - consumed.y) / 1.53333f, returnVelocity, 0.01f)
    }
}
