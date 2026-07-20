package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged

@Composable
internal fun AdvancedGlassSceneLayer(
    controller: AdvancedGlassController,
    modifier: Modifier = Modifier,
    motion: AdvancedGlassSceneMotion = AdvancedGlassSceneMotion.None,
    disableStretchOverscroll: Boolean = false,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundBackdrop = rememberAdvancedGlassBackdrop()
    val contentBackdrop = rememberAdvancedGlassBackdrop()
    var sceneHeightPx by remember { mutableIntStateOf(0) }

    AdvancedGlassHost(
        controller = controller,
        backgroundBackdrop = backgroundBackdrop,
        contentBackdrop = contentBackdrop,
        disableStretchOverscroll = disableStretchOverscroll
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { size -> sceneHeightPx = size.height }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipSceneReveal(motion.revealTopFraction)
                    .captureAdvancedGlassBackdrop(backgroundBackdrop),
                content = background
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = sceneHeightPx *
                            motion.contentTranslationYFraction.coerceIn(0f, 1f)
                        scaleX = motion.contentScale.coerceIn(0.8f, 1f)
                        scaleY = motion.contentScale.coerceIn(0.8f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .captureAdvancedGlassBackdrop(contentBackdrop),
                content = content
            )
        }
    }
}

private fun Modifier.clipSceneReveal(revealTopFraction: Float): Modifier = drawWithContent {
    val revealTop = size.height * revealTopFraction.coerceIn(0f, 1f)
    clipRect(top = revealTop) {
        this@drawWithContent.drawContent()
    }
}
