package moe.ouom.neriplayer.core.download.storage.tree.cache

internal object TreeChildNameRefreshMerger {
    fun mergeAfterRefresh(
        refreshedNames: Collection<String>,
        cachedNames: Collection<String>?,
        cachedNamesComplete: Boolean?,
        refreshedComplete: Boolean
    ): TreeChildNameRefresh {
        if (refreshedComplete && cachedNamesComplete != false) {
            return TreeChildNameRefresh(
                names = refreshedNames.toCollection(linkedSetOf()),
                isComplete = true
            )
        }
        return TreeChildNameRefresh(
            names = (refreshedNames + cachedNames.orEmpty()).toCollection(linkedSetOf()),
            isComplete = false
        )
    }
}
