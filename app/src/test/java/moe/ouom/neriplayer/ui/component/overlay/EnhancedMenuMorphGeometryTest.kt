package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/* 12px window margin, 6px anchor gap, no caller offset; a 200x100 menu in a 400x800 window */
class EnhancedMenuMorphGeometryTest {
    private val window = IntSize(400, 800)
    private val menu = IntSize(200, 100)
    private val spacing = MenuSpacing(margin = 12, gap = 6, offsetX = 0, offsetY = 0)

    private fun place(anchor: IntRect, size: IntSize = menu, ltr: Boolean = true) =
        placeEnhancedMenu(anchor, window, size, ltr, spacing)

    @Test
    fun opensBelowAndStartAlignedWithTheAnchor() {
        assertEquals(IntRect(20, 154, 220, 254), place(IntRect(20, 100, 68, 148)))
    }

    @Test
    fun flipsToEndAlignedNearTheTrailingEdge() {
        assertEquals(188, place(IntRect(340, 100, 388, 148)).left)
    }

    @Test
    fun opensAboveWhenTheAnchorSitsNearTheBottom() {
        assertEquals(634, place(IntRect(20, 740, 68, 788)).top)
    }

    @Test
    fun mirrorsAlignmentForRightToLeftLayouts() {
        assertEquals(180, place(IntRect(332, 100, 380, 148), ltr = false).left)
    }

    @Test
    fun clampsAnOversizedMenuIntoTheWindowMargin() {
        assertEquals(12, place(IntRect(20, 100, 68, 148), size = IntSize(200, 900)).top)
    }

    @Test
    fun morphRunsFromTheAnchorCapsuleToTheMenuSlot() {
        val anchor = IntRect(20, 100, 68, 148)
        val slot = IntRect(20, 154, 220, 254)

        assertEquals(anchor, morphFrame(anchor, slot, menu, 0f))
        assertEquals(slot, morphFrame(anchor, slot, menu, 1f))
        assertEquals(IntRect(20, 127, 144, 201), morphFrame(anchor, slot, menu, 0.5f))
        // 回弹越过终点时围绕卡片中心放大，不会漂离锚点方向
        assertEquals(IntRect(15, 152, 225, 256), morphFrame(anchor, slot, menu, 1.05f))
    }
}
