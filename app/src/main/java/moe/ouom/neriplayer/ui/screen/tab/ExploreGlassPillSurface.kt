package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface

private val ExplorePillShape = RoundedCornerShape(999.dp)

@Composable
internal fun ExploreGlassPillSurface(
    fallbackColor: Color,
    tintColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ExploreTag,
            shape = ExplorePillShape,
            fallbackColor = fallbackColor,
            tintColor = tintColor
        ) {
            Surface(
                modifier = Modifier
                    .clip(ExplorePillShape)
                    .indication(interactionSource, ripple()),
                shape = ExplorePillShape,
                color = Color.Transparent,
                contentColor = contentColor,
                border = border,
                content = content
            )
        }
    }
}
