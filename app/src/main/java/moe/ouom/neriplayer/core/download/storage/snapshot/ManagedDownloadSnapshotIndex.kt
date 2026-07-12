package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import org.json.JSONObject

internal object ManagedDownloadSnapshotIndex {
    fun compose(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val metadataEntriesByAudioName = metadataEntries.associateBy { entry ->
            entry.name.removeSuffix(METADATA_SUFFIX)
        }
        val coverEntriesByName = coverEntries.associateBy(ManagedDownloadStorage.StoredEntry::name)
        val lyricEntriesByName = lyricEntries.associateBy(ManagedDownloadStorage.StoredEntry::name)
        val audioEntriesByStableKey = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesBySongId = mutableMapOf<Long, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesByMediaUri = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesByRemoteTrackKey = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesWithoutMetadata = mutableListOf<ManagedDownloadStorage.StoredEntry>()

        audioEntries.forEach { entry ->
            val metadata = metadataByAudioName[entry.name]
            if (metadata == null) {
                audioEntriesWithoutMetadata += entry
                return@forEach
            }

            metadata.stableKey?.let { key ->
                audioEntriesByStableKey.getOrPut(key) { mutableListOf() } += entry
            }
            metadata.songId?.takeIf { it > 0L }?.let { songId ->
                audioEntriesBySongId.getOrPut(songId) { mutableListOf() } += entry
            }
            metadata.mediaUri?.let { mediaUri ->
                audioEntriesByMediaUri.getOrPut(mediaUri) { mutableListOf() } += entry
            }
            buildRemoteTrackKey(
                channelId = metadata.channelId,
                audioId = metadata.audioId,
                subAudioId = metadata.subAudioId
            )?.let { remoteTrackKey ->
                audioEntriesByRemoteTrackKey.getOrPut(remoteTrackKey) { mutableListOf() } += entry
            }
        }

        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = audioEntries,
            audioEntriesByLookupKey = buildMap {
                audioEntries.forEach { entry ->
                    put(entry.reference, entry)
                    put(entry.mediaUri, entry)
                    entry.localFilePath?.let { put(it, entry) }
                }
            },
            metadataEntriesByAudioName = metadataEntriesByAudioName,
            metadataByAudioName = metadataByAudioName,
            audioEntriesWithoutMetadata = audioEntriesWithoutMetadata,
            audioEntriesByStableKey = audioEntriesByStableKey,
            audioEntriesBySongId = audioEntriesBySongId,
            audioEntriesByMediaUri = audioEntriesByMediaUri,
            audioEntriesByRemoteTrackKey = audioEntriesByRemoteTrackKey,
            coverEntriesByName = coverEntriesByName,
            lyricEntriesByName = lyricEntriesByName,
            knownReferences = buildSet {
                audioEntries.forEach { add(it.reference) }
                metadataEntries.forEach { add(it.reference) }
                coverEntries.forEach { add(it.reference) }
                lyricEntries.forEach { add(it.reference) }
            }
        )
    }

    fun serializePayload(
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String {
        return JSONObject().apply {
            put("cacheKey", cacheKey)
            put("audioEntries", ManagedDownloadStorageJsonCodec.storedEntriesToJsonArray(snapshot.audioEntries))
            put(
                "metadataEntries",
                ManagedDownloadStorageJsonCodec.storedEntriesToJsonArray(
                    snapshot.metadataEntriesByAudioName.values.toList()
                )
            )
            put("metadataByAudioName", JSONObject().apply {
                snapshot.metadataByAudioName.forEach { (audioName, metadata) ->
                    put(audioName, ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata))
                }
            })
            put(
                "coverEntries",
                ManagedDownloadStorageJsonCodec.storedEntriesToJsonArray(snapshot.coverEntriesByName.values.toList())
            )
            put(
                "lyricEntries",
                ManagedDownloadStorageJsonCodec.storedEntriesToJsonArray(snapshot.lyricEntriesByName.values.toList())
            )
        }.toString()
    }

    fun deserializePayload(
        raw: String,
        expectedKey: String? = null
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        val root = JSONObject(raw)
        val cacheKey = root.optString("cacheKey").takeIf(String::isNotBlank) ?: return null
        if (expectedKey != null && expectedKey != cacheKey) {
            return null
        }

        val audioEntries = ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(root.optJSONArray("audioEntries"))
        val metadataEntries = ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(
            root.optJSONArray("metadataEntries")
        )
        val metadataRoot = root.optJSONObject("metadataByAudioName") ?: JSONObject()
        val metadataByAudioName = buildMap {
            metadataRoot.keys().forEach { audioName ->
                metadataRoot.optJSONObject(audioName)
                    ?.let(ManagedDownloadStorageJsonCodec::downloadedAudioMetadataFromJsonObject)
                    ?.let { put(audioName, it) }
            }
        }
        val coverEntries = ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(root.optJSONArray("coverEntries"))
        val lyricEntries = ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(root.optJSONArray("lyricEntries"))
        return cacheKey to compose(
            audioEntries = audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries
        )
    }

    fun applyMetadataWrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        metadataEntry: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val targetAudioName = metadataEntry.name.removeSuffix(METADATA_SUFFIX)
        return compose(
            audioEntries = snapshot.audioEntries,
            metadataEntries = snapshot.metadataEntriesByAudioName.values
                .filterNot { it.name.removeSuffix(METADATA_SUFFIX) == targetAudioName } +
                metadataEntry,
            metadataByAudioName = snapshot.metadataByAudioName.toMutableMap().apply {
                put(targetAudioName, metadata)
            },
            coverEntries = snapshot.coverEntriesByName.values.toList(),
            lyricEntries = snapshot.lyricEntriesByName.values.toList()
        )
    }

    fun applyStoredEntryWrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedEntry: ManagedDownloadStorage.StoredEntry,
        bucket: ManagedDownloadStorage.SnapshotEntryBucket
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return when (bucket) {
            ManagedDownloadStorage.SnapshotEntryBucket.AUDIO -> compose(
                audioEntries = replaceStoredEntry(snapshot.audioEntries, storedEntry),
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = snapshot.lyricEntriesByName.values.toList()
            )

            ManagedDownloadStorage.SnapshotEntryBucket.COVER -> compose(
                audioEntries = snapshot.audioEntries,
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = replaceStoredEntry(snapshot.coverEntriesByName.values, storedEntry),
                lyricEntries = snapshot.lyricEntriesByName.values.toList()
            )

            ManagedDownloadStorage.SnapshotEntryBucket.LYRIC -> compose(
                audioEntries = snapshot.audioEntries,
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = replaceStoredEntry(snapshot.lyricEntriesByName.values, storedEntry)
            )
        }
    }

    fun applyReferenceDeletes(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        references: Set<String>
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        if (references.isEmpty()) {
            return snapshot
        }
        val deletedMetadataAudioNames = snapshot.metadataEntriesByAudioName.values
            .filter { entry -> entry.reference in references }
            .mapTo(linkedSetOf()) { entry -> entry.name.removeSuffix(METADATA_SUFFIX) }
        return compose(
            audioEntries = snapshot.audioEntries.filterNot { entry -> entry.reference in references },
            metadataEntries = snapshot.metadataEntriesByAudioName.values
                .filterNot { entry -> entry.reference in references },
            metadataByAudioName = snapshot.metadataByAudioName.filterKeys { audioName ->
                audioName !in deletedMetadataAudioNames
            },
            coverEntries = snapshot.coverEntriesByName.values
                .filterNot { entry -> entry.reference in references },
            lyricEntries = snapshot.lyricEntriesByName.values
                .filterNot { entry -> entry.reference in references }
        )
    }

    private fun replaceStoredEntry(
        entries: Collection<ManagedDownloadStorage.StoredEntry>,
        storedEntry: ManagedDownloadStorage.StoredEntry
    ): List<ManagedDownloadStorage.StoredEntry> {
        return entries
            .filterNot { entry ->
                entry.reference == storedEntry.reference || entry.name == storedEntry.name
            } + storedEntry
    }

    fun buildRemoteTrackKey(
        channelId: String?,
        audioId: String?,
        subAudioId: String?
    ): String? {
        val resolvedChannelId = channelId?.takeIf { it.isNotBlank() } ?: return null
        val resolvedAudioId = audioId?.takeIf { it.isNotBlank() }.orEmpty()
        val resolvedSubAudioId = subAudioId?.takeIf { it.isNotBlank() }.orEmpty()
        if (resolvedAudioId.isBlank() && resolvedSubAudioId.isBlank()) {
            return null
        }
        return "$resolvedChannelId|$resolvedAudioId|$resolvedSubAudioId"
    }
}
