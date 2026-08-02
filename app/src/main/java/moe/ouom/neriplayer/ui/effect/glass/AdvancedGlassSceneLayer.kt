package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect

@Composable
internal fun AdvancedGlassSceneLayer(
    controller: AdvancedGlassController,
    modifier: Modifier = Modifier,
    motion: AdvancedGlassSceneMotion = AdvancedGlassSceneMotion.None,
    disableStretchOverscroll: Boolean = false,
    // 固定背景场景不画自己的壁纸, 玻璃面直接采样根层静止壁纸, 只用于 tab 根列表
    fixedBackground: Boolean = false,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    if (fixedBackground) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .clipSceneReveal(motion.revealTopFraction)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = size.height *
                            motion.contentTranslationYFraction.coerceIn(0f, 1f)
                        scaleX = motion.contentScale.coerceIn(0.8f, 1f)
                        scaleY = motion.contentScale.coerceIn(0.8f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                content = content
            )
        }
        return
    }

    val backgroundBackdrop = rememberAdvancedGlassBackdrop()
    val contentBackdrop = rememberAdvancedGlassBackdrop()

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
                        translationY = size.height *
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
