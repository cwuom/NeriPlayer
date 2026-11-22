package moe.ouom.neriplayer.core.player.lan

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation

internal object LanPlaybackArtwork {
    data class Ready(val trackKey: String, val bitmap: Bitmap)

    val ready = MutableStateFlow<Ready?>(null)

    fun update(trackKey: String?, bitmap: Bitmap?) {
        ready.value = if (trackKey != null && bitmap != null) Ready(trackKey, bitmap) else null
    }
}

internal fun lanPresentationUpdates(
    initial: LanSharedPresentation,
    resolveLyrics: suspend () -> LanSharedPresentation,
    covers: Flow<LanFileResource>,
): Flow<LanSharedPresentation> = combine(
    flow { emit(initial); emit(resolveLyrics()) },
    covers.map<LanFileResource, LanFileResource?> { it }.onStart { emit(null) },
) { lyrics, cover -> lyrics.copy(cover = cover) }.distinctUntilChanged()
