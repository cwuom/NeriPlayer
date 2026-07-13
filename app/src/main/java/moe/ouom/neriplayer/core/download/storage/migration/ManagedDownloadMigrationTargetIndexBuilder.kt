package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle

internal object ManagedDownloadMigrationTargetIndexBuilder {
    fun build(
        targetRoot: ManagedDownloadRootHandle,
        listChildren: (ManagedDownloadRootHandle) -> List<ManagedDownloadStorage.StoredEntry>,
        findSubdirectories: (ManagedDownloadRootHandle, String, Boolean) -> List<ManagedDownloadRootHandle>
    ): ManagedMigrationTargetIndex {
        val rootEntriesByName = listChildren(targetRoot)
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        val coverEntriesByName = findSubdirectories(targetRoot, COVER_SUBDIRECTORY, true)
            .flatMap(listChildren)
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        val lyricEntriesByName = findSubdirectories(targetRoot, LYRIC_SUBDIRECTORY, true)
            .flatMap(listChildren)
            .associateBy(ManagedDownloadStorage.StoredEntry::name)
        return ManagedMigrationTargetIndex(
            rootEntriesByName = rootEntriesByName,
            coverEntriesByName = coverEntriesByName,
            lyricEntriesByName = lyricEntriesByName
        )
    }
}
