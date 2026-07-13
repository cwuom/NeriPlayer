package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val playlistExportListMaxHeight = 320.dp

internal fun Modifier.playlistExportListHeight(): Modifier =
    heightIn(max = playlistExportListMaxHeight)
