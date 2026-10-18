package moe.ouom.neriplayer.ui.component.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign

/**
 * Hosts a search field. Over a custom background in the clean style the field becomes a glass
 * region so the advanced blur reaches it; elsewhere it is a plain box with no extra cost.
 */
@Composable
fun NeriSearchFieldGlass(
    modifier: Modifier = Modifier,
    shape: Shape = NeriDesign.tokens.groupShape,
    content: @Composable () -> Unit
) {
    if (NeriDesign.isClean && LocalHasCustomBackground.current) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = modifier,
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            tintColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) { content() }
    } else {
        Box(modifier = modifier) { content() }
    }
}
