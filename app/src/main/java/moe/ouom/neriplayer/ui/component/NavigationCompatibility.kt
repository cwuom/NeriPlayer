package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.navigation.Destinations

@Composable
fun NeriBottomBar(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    onItemSelected: (Destinations) -> Unit,
    modifier: Modifier = Modifier,
    selectAlpha: Float = 1f
) {
    moe.ouom.neriplayer.ui.component.navigation.NeriBottomBar(
        items = items,
        currentDestination = currentDestination,
        onItemSelected = onItemSelected,
        modifier = modifier,
        selectAlpha = selectAlpha
    )
}

