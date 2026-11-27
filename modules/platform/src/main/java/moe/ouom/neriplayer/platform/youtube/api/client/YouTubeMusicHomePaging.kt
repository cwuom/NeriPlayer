package moe.ouom.neriplayer.platform.youtube.api.client

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicBrowseResponse
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeCursor
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomePage
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelfContinuation
import moe.ouom.neriplayer.platform.youtube.api.parser.YouTubeMusicParser

internal fun YouTubeMusicBrowseResponse.toHomePage(cursor: YouTubeMusicHomeCursor?): YouTubeMusicHomePage {
    val position = cursor?.copy(locale = requestLocale)
        ?: YouTubeMusicHomeCursor("", requestLocale, emptySet())
    val shelf = position.activeShelf
    if (shelf != null) {
        val items = YouTubeMusicParser.parseHomeShelfContinuationItems(root).distinctHomeItems()
        val token = YouTubeMusicParser.extractHomeShelfContinuation(root)
        val next = if (token != null && token !in shelf.visitedTokens) {
            position.copy(token = token, activeShelf = shelf.copy(token = token, visitedTokens = shelf.visitedTokens + token))
        } else position.nextPendingShelf()
        return YouTubeMusicHomePage(listOf(YouTubeMusicHomeShelf(shelf.title, items, shelf.key)), next)
    }

    val pending = position.pendingShelves.toMutableList()
    val shelves = YouTubeMusicParser.parseHomeShelfPages(root).mapIndexed { index, parsed ->
        val continuation = parsed.continuation?.let { token ->
            YouTubeMusicHomeShelfContinuation("shelf-${position.nextShelfIndex + index}", parsed.title, token)
        }
        continuation?.let(pending::add)
        YouTubeMusicHomeShelf(parsed.title, parsed.items.distinctHomeItems(), continuation?.key)
    }
    val updated = position.copy(pendingShelves = pending, nextShelfIndex = position.nextShelfIndex + shelves.size)
    val token = YouTubeMusicParser.extractHomeContinuation(root)
    val next = if (token != null && token !in position.visitedTokens) {
        updated.copy(token = token, visitedTokens = position.visitedTokens + token)
    } else updated.nextPendingShelf()
    return YouTubeMusicHomePage(shelves, next)
}

private fun YouTubeMusicHomeCursor.nextPendingShelf(): YouTubeMusicHomeCursor? {
    val shelf = pendingShelves.firstOrNull() ?: return null
    return copy(token = shelf.token, activeShelf = shelf, pendingShelves = pendingShelves.drop(1))
}

private fun List<YouTubeMusicHomeItem>.distinctHomeItems() = distinctBy { Triple(it.title, it.browseId, it.videoId) }
