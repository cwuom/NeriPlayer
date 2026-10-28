package moe.ouom.neriplayer.ui.component.playlist

import androidx.annotation.StringRes
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.i18n.UiText

internal enum class PlaylistNameError {
    EMPTY,
    FAVORITES_RESERVED,
    LOCAL_FILES_RESERVED,
    DUPLICATE;

    fun message(): UiText = when (this) {
        EMPTY -> UiText.Resource(CoreCommonR.string.playlist_name_empty)
        FAVORITES_RESERVED -> reservedName(CoreCommonR.string.favorite_my_music)
        LOCAL_FILES_RESERVED -> reservedName(CoreCommonR.string.local_files)
        DUPLICATE -> UiText.Resource(CoreCommonR.string.library_name_exists)
    }

    private fun reservedName(@StringRes nameRes: Int) = UiText.Resource(
        CoreCommonR.string.library_name_reserved, listOf(UiText.Resource(nameRes))
    )
}
