package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.navigation.DEFAULT_BOTTOM_BAR_SELECTION_ALPHA

@Composable
fun NeriBottomBar(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    onItemSelected: (Destinations) -> Unit,
    modifier: Modifier = Modifier,
    selectAlpha: Float = DEFAULT_BOTTOM_BAR_SELECTION_ALPHA
) {
    moe.ouom.neriplayer.ui.component.navigation.NeriBottomBar(
        items = items,
        currentDestination = currentDestination,
        onItemSelected = onItemSelected,
        modifier = modifier,
        selectAlpha = selectAlpha
    )
}
