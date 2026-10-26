package moe.ouom.neriplayer.ui.viewmodel

import androidx.annotation.StringRes
import moe.ouom.neriplayer.ui.i18n.UiText

internal fun feedbackText(@StringRes id: Int, vararg arguments: Any?): UiText =
    UiText.Resource(id, arguments.toList())
