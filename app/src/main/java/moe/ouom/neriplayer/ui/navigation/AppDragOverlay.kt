package moe.ouom.neriplayer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

internal val LocalAppDragOverlay = staticCompositionLocalOf<AppDragOverlay?> { null }

@Stable
internal class AppDragOverlay(val layer: GraphicsLayer) {
    private var origin by mutableStateOf(Offset.Zero)
    private var drawing by mutableStateOf<Drawing?>(null)

    internal fun updateOrigin(position: Offset) {
        origin = position
    }

    internal fun show(owner: Any, dragging: Boolean, position: () -> Offset?) {
        if (drawing?.dragging == true && !dragging) return
        drawing = Drawing(owner, dragging, position)
    }

    internal fun owns(owner: Any): Boolean = drawing?.owner === owner

    internal fun clear(owner: Any) {
        if (owns(owner)) drawing = null
    }

    internal fun draw(scope: DrawScope) {
        val position = drawing?.position?.invoke()?.minus(origin) ?: return
        with(scope) {
            translate(position.x, position.y) { drawLayer(layer) }
        }
    }

    private data class Drawing(
        val owner: Any,
        val dragging: Boolean,
        val position: () -> Offset?
    )
}

@Composable
internal fun AppDragOverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // 图层由稳定宿主持有，歌曲行重排或离开视口时不会释放正在绘制的卡片
    val layer = rememberGraphicsLayer()
    val overlay = remember(layer) { AppDragOverlay(layer) }
    Box(
        modifier = modifier
            .onGloballyPositioned { overlay.updateOrigin(it.positionInRoot()) }
            .drawWithContent {
                drawContent()
                overlay.draw(this)
            }
    ) {
        CompositionLocalProvider(LocalAppDragOverlay provides overlay, content = content)
    }
}
