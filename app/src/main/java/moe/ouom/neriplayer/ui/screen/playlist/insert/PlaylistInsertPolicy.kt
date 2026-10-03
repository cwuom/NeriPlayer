package moe.ouom.neriplayer.ui.screen.playlist.insert

internal data class PlaylistInsertPreview(
    val sourceKeys: List<String>,
    val selectedKeys: Set<String>,
    val movedKeys: List<String>,
    val orderedKeys: List<String>,
    val startPosition: Int
)

internal fun resolvePlaylistInsertPosition(
    input: String,
    songCount: Int,
    selectedCount: Int
): Int? {
    if (selectedCount <= 0 || selectedCount > songCount) return null
    val position = input.trim().toIntOrNull() ?: return null
    return position.takeIf { it in 1..(songCount - selectedCount + 1) }
}

internal fun createPlaylistInsertPreview(
    sourceKeys: List<String>,
    selectedKeys: Set<String>,
    startPosition: Int
): PlaylistInsertPreview? {
    val sourceKeySet = sourceKeys.toSet()
    if (sourceKeySet.size != sourceKeys.size || sourceKeySet.any(String::isBlank)) return null
    if (selectedKeys.isEmpty() || !sourceKeySet.containsAll(selectedKeys)) return null
    if (startPosition !in 1..(sourceKeys.size - selectedKeys.size + 1)) return null

    val movedKeys = sourceKeys.filter { it in selectedKeys }
    val remainingKeys = sourceKeys.filterNot { it in selectedKeys }
    val insertionIndex = startPosition - 1
    val orderedKeys = buildList(sourceKeys.size) {
        addAll(remainingKeys.subList(0, insertionIndex))
        addAll(movedKeys)
        addAll(remainingKeys.subList(insertionIndex, remainingKeys.size))
    }
    return PlaylistInsertPreview(
        sourceKeys = sourceKeys.toList(),
        selectedKeys = selectedKeys.toSet(),
        movedKeys = movedKeys,
        orderedKeys = orderedKeys,
        startPosition = startPosition
    )
}

internal fun isPlaylistInsertPreviewCurrent(
    preview: PlaylistInsertPreview,
    currentKeys: List<String>,
    selectedKeys: Set<String> = preview.selectedKeys
): Boolean {
    if (preview.sourceKeys != currentKeys || preview.selectedKeys != selectedKeys) return false
    return createPlaylistInsertPreview(currentKeys, selectedKeys, preview.startPosition) == preview
}
