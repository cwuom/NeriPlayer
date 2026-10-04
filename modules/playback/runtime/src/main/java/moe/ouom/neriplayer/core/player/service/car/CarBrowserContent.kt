package moe.ouom.neriplayer.core.player.service.car

import moe.ouom.neriplayer.core.player.service.car.library.CarLibraryItem
import moe.ouom.neriplayer.core.player.service.car.library.CarLibraryPaging
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary

private const val ROOT_CHILDREN_DEFAULT = 4
private const val FLAG_BROWSABLE = 1
private const val FLAG_PLAYABLE = 2

internal fun carBrowserChildren(
    library: CarMediaLibrary,
    parentId: String,
    rootId: String,
    maxRootChildren: Int?,
    supportedRootFlags: Int?,
): List<CarLibraryItem> {
    if (parentId != rootId) return library.children(parentId)
    val limit = maxRootChildren?.takeIf { it > 0 }?.coerceAtMost(CarLibraryPaging.PAGE_SIZE)
    val playableOnly = supportedRootFlags != null &&
        supportedRootFlags and FLAG_PLAYABLE != 0 && supportedRootFlags and FLAG_BROWSABLE == 0
    if (playableOnly) {
        val directories = when (parentId) {
            CarMediaIds.ROOT, CarMediaIds.CATALOGUE ->
                listOf(CarMediaIds.QUEUE, CarMediaIds.HISTORY, CarMediaIds.OFFLINE)
            else -> listOf(parentId)
        }
        return playableRootChildren(library, directories, limit ?: ROOT_CHILDREN_DEFAULT)
    }
    if (parentId == CarMediaIds.ROOT && limit != null && limit < ROOT_CHILDREN_DEFAULT) {
        return listOfNotNull(library.getItem(CarMediaIds.CATALOGUE))
    }
    val children = library.children(parentId)
    return if (parentId in listOf(CarMediaIds.HISTORY, CarMediaIds.OFFLINE) && limit != null) {
        children.take(limit)
    } else children
}

private fun playableRootChildren(
    library: CarMediaLibrary,
    directories: List<String>,
    limit: Int,
): List<CarLibraryItem> = buildList {
    val pending = ArrayDeque<CarLibraryItem>()
    directories.asReversed().forEach { id -> library.getItem(id)?.let(pending::addFirst) }
    while (pending.isNotEmpty() && size < limit) {
        val item = pending.removeFirst()
        if (item.isPlayable) add(item)
        else library.children(item.mediaId).asReversed().forEach(pending::addFirst)
    }
}
