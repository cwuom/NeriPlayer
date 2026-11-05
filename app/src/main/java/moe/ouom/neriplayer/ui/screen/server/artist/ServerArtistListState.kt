package moe.ouom.neriplayer.ui.screen.server.artist

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal data class ServerArtistListPosition(val index: Int, val offset: Int, val itemKey: String?)

internal class ServerArtistListState(restored: ServerArtistListPosition? = null) {
    val listState = LazyListState(restored?.index ?: 0, restored?.offset ?: 0)
    private var pendingPosition = restored

    fun savedPosition(): ServerArtistListPosition {
        pendingPosition?.let { return it }
        val index = listState.firstVisibleItemIndex
        return ServerArtistListPosition(index, listState.firstVisibleItemScrollOffset,
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String)
    }

    suspend fun restorePosition(itemKeys: List<String>) {
        val pending = pendingPosition ?: return
        val index = pending.itemKey?.let(itemKeys::indexOf)?.takeIf { it >= 0 } ?: pending.index
        listState.scrollToItem(index.coerceIn(0, itemKeys.lastIndex.coerceAtLeast(0)), pending.offset)
        pendingPosition = null
    }

    fun cancelPendingRestore() { pendingPosition = null }
}

private val serverArtistListStateSaver = listSaver<ServerArtistListState, Any?>(
    save = { state -> state.savedPosition().let { listOf(it.index, it.offset, it.itemKey) } },
    restore = { saved ->
        val index = (saved.getOrNull(0) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        val offset = (saved.getOrNull(1) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        ServerArtistListState(ServerArtistListPosition(index, offset, saved.getOrNull(2) as? String))
    }
)

@Composable
internal fun rememberServerArtistListState(location: String): ServerArtistListState =
    rememberSaveable(location, saver = serverArtistListStateSaver) { ServerArtistListState() }
