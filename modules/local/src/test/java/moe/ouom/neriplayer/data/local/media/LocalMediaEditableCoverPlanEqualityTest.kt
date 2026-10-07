package moe.ouom.neriplayer.data.local.media

import com.kyant.taglib.Picture
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.EditableCoverWritePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalMediaEditableCoverPlanEqualityTest {
    private val front = Picture(data = byteArrayOf(1, 2), description = "", pictureType = "Front Cover", mimeType = "image/jpeg")
    private val back = Picture(data = byteArrayOf(3), description = "back", pictureType = "Back Cover", mimeType = "image/png")

    @Test
    fun `update plans compare their picture arrays by content`() {
        val plan = EditableCoverWritePlan.Update(arrayOf(front), arrayOf(back))
        val samePictures = EditableCoverWritePlan.Update(arrayOf(front), arrayOf(back))

        assertEquals(plan, plan)
        assertEquals(samePictures, plan)
        assertEquals(samePictures.hashCode(), plan.hashCode())
    }

    @Test
    fun `update plans differ from other plans and from different pictures`() {
        val plan = EditableCoverWritePlan.Update(arrayOf(front), arrayOf(back))

        assertNotEquals(plan, null)
        assertNotEquals(plan, EditableCoverWritePlan.Unchanged)
        assertNotEquals(plan, EditableCoverWritePlan.Update(arrayOf(back), arrayOf(back)))
        assertNotEquals(plan, EditableCoverWritePlan.Update(arrayOf(front), arrayOf(front, back)))
    }
}
