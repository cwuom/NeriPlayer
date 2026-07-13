package moe.ouom.neriplayer.ui.component

import androidx.compose.ui.Modifier
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker as newBottomSheetDragBlocker
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard as newBottomSheetScrollGuard

fun Modifier.bottomSheetScrollGuard(
    allowDownwardToParent: () -> Boolean = { false }
): Modifier =
    newBottomSheetScrollGuard(allowDownwardToParent)

fun Modifier.bottomSheetDragBlocker(): Modifier =
    newBottomSheetDragBlocker()
