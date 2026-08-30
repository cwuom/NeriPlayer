package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import org.json.JSONObject

internal object ManagedDownloadSnapshotIndex {
    fun compose(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        rootEntriesComplete: Boolean = true,
        sidecarEntriesComplete: Boolean = true,
        pendingAudioEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        pendingMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata> =
            emptyMap()
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val normalizedPendingAudioEntries = (pendingAudioEntries + audioEntries.filter {
            it.isPendingAudioWrite
        }).distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        val pendingAudioNames = normalizedPendingAudioEntries
            .mapTo(hashSetOf(), ManagedDownloadStorage.StoredEntry::logicalName)
        val normalizedAudioEntries = audioEntries
            .filterNot(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        val metadataEntriesWithAudioNames = metadataEntries
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                    audioName to entry
                }
            }
        val metadataEntriesByAudioName = metadataEntriesWithAudioNames
            .filter { (audioName, entry) ->
                !ManagedDownloadTreeNaming.isPendingMetadataName(
                    actualName = entry.name,
                    audioName = audioName
                ) || audioName in pendingAudioNames
            }
            .groupBy { it.first }
            .mapValues { (audioName, entries) ->
                entries.minWithOrNull(
                    compareBy<Pair<String, ManagedDownloadStorage.StoredEntry>>(
                        { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                        { it.second.name }
                    )
                )!!.second
            }
        // 只有和 pending 音频同时存在的 pending metadata 才能遮蔽正式 metadata。
        // 孤儿凭据可能来自进程中断，不能让它把可播放的旧歌曲从索引中移除
        // 这里仍需记录未进入 metadataEntriesByAudioName 的孤儿 pending 名称，
        // 否则调用方传入的旧 metadataByAudioName 会把孤儿凭据重新当成正式数据
        val metadataGroupsByAudioName = metadataEntriesWithAudioNames.groupBy { it.first }
        val pendingMetadataNamesFromEntries = metadataGroupsByAudioName
            .filter { (audioName, entries) ->
                val hasPending = entries.any { (_, entry) ->
                    ManagedDownloadTreeNaming.isPendingMetadataName(
                        actualName = entry.name,
                        audioName = audioName
                    )
                }
                val hasCanonical = entries.any { (_, entry) ->
                    !ManagedDownloadTreeNaming.isPendingMetadataName(
                        actualName = entry.name,
                        audioName = audioName
                    )
                }
                hasPending && (!hasCanonical || audioName in pendingAudioNames)
            }
            .keys
        val normalizedPendingMetadataByAudioName = pendingMetadataByAudioName
            .filterKeys { audioName -> audioName in pendingAudioNames }
        val pendingMetadataNames = pendingMetadataNamesFromEntries +
            normalizedPendingMetadataByAudioName.keys
        val normalizedMetadataByAudioName = metadataByAudioName.filterKeys { audioName ->
            audioName !in pendingMetadataNames || audioName in pendingAudioNames
        }
        val coverEntriesByName = coverEntries.associateBy(ManagedDownloadStorage.StoredEntry::name)
        val lyricEntriesByName = lyricEntries.associateBy(ManagedDownloadStorage.StoredEntry::name)
        val audioEntriesByStableKey = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesBySongId = mutableMapOf<Long, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesByMediaUri = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesByRemoteTrackKey = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesWithoutMetadata = mutableListOf<ManagedDownloadStorage.StoredEntry>()

        normalizedAudioEntries.forEach { entry ->
            // metadata 可能按逻辑文件名保存，索引时要同时接受两种命名
            val metadata = normalizedMetadataByAudioName[entry.name]
                ?: normalizedMetadataByAudioName[entry.logicalName]
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
            audioEntries = normalizedAudioEntries,
            audioEntriesByLookupKey = buildMap {
                normalizedAudioEntries.forEach { entry ->
                    put(entry.reference, entry)
                    put(entry.mediaUri, entry)
                    entry.localFilePath?.let { put(it, entry) }
                }
            },
            metadataEntriesByAudioName = metadataEntriesByAudioName,
            metadataByAudioName = normalizedMetadataByAudioName,
            audioEntriesWithoutMetadata = audioEntriesWithoutMetadata,
            audioEntriesByStableKey = audioEntriesByStableKey,
            audioEntriesBySongId = audioEntriesBySongId,
            audioEntriesByMediaUri = audioEntriesByMediaUri,
            audioEntriesByRemoteTrackKey = audioEntriesByRemoteTrackKey,
            coverEntriesByName = coverEntriesByName,
            lyricEntriesByName = lyricEntriesByName,
            knownReferences = buildSet {
                normalizedAudioEntries.forEach { add(it.reference) }
                normalizedPendingAudioEntries.forEach { add(it.reference) }
                metadataEntries.forEach { add(it.reference) }
                coverEntries.forEach { add(it.reference) }
                lyricEntries.forEach { add(it.reference) }
            },
            rootEntriesComplete = rootEntriesComplete,
            sidecarEntriesComplete = sidecarEntriesComplete,
            pendingAudioEntries = normalizedPendingAudioEntries,
            pendingMetadataByAudioName = normalizedPendingMetadataByAudioName
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
                "pendingAudioEntries",
                ManagedDownloadStorageJsonCodec.storedEntriesToJsonArray(snapshot.pendingAudioEntries)
            )
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
            put("pendingMetadataByAudioName", JSONObject().apply {
                snapshot.pendingMetadataByAudioName.forEach { (audioName, metadata) ->
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
            put("rootEntriesComplete", snapshot.rootEntriesComplete)
            put("sidecarEntriesComplete", snapshot.sidecarEntriesComplete)
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
        val pendingAudioEntries = ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(
            root.optJSONArray("pendingAudioEntries")
        )
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
        val pendingMetadataRoot = root.optJSONObject("pendingMetadataByAudioName") ?: JSONObject()
        val pendingMetadataByAudioName = buildMap {
            pendingMetadataRoot.keys().forEach { audioName ->
                pendingMetadataRoot.optJSONObject(audioName)
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
            lyricEntries = lyricEntries,
            rootEntriesComplete = root.optBoolean("rootEntriesComplete", true),
            sidecarEntriesComplete = root.optBoolean("sidecarEntriesComplete", true),
            pendingAudioEntries = pendingAudioEntries,
            pendingMetadataByAudioName = pendingMetadataByAudioName
        )
    }

    fun applyMetadataWrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        metadataEntry: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val targetAudioName = ManagedDownloadTreeNaming.metadataAudioName(metadataEntry.name)
            ?: return snapshot
        val isPendingMetadataWrite = ManagedDownloadTreeNaming.isPendingMetadataName(
            actualName = metadataEntry.name,
            audioName = targetAudioName
        )
        val existingMetadataEntry = snapshot.metadataEntriesByAudioName[targetAudioName]
        val hasCanonicalMetadata = existingMetadataEntry != null &&
            !ManagedDownloadTreeNaming.isPendingMetadataName(
                actualName = existingMetadataEntry.name,
                audioName = targetAudioName
            )
        val metadataEntries = if (isPendingMetadataWrite && hasCanonicalMetadata) {
            snapshot.metadataEntriesByAudioName.values.toList()
        } else {
            snapshot.metadataEntriesByAudioName.values
                .filterNot {
                    ManagedDownloadTreeNaming.metadataAudioName(it.name) == targetAudioName
                } + metadataEntry
        }
        val pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
            .toMutableMap()
            .apply {
                if (isPendingMetadataWrite) {
                    put(targetAudioName, metadata)
                } else if (snapshot.pendingAudioEntries.any { it.logicalName == targetAudioName }) {
                    // pending 音频已通过 core 提交后，必须立即改用同一份持久化凭据
                    put(targetAudioName, metadata)
                } else {
                    remove(targetAudioName)
                }
            }
        return compose(
            audioEntries = snapshot.audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = snapshot.metadataByAudioName.toMutableMap().apply {
                if (!isPendingMetadataWrite || !hasCanonicalMetadata) {
                    put(targetAudioName, metadata)
                }
            },
            coverEntries = snapshot.coverEntriesByName.values.toList(),
            lyricEntries = snapshot.lyricEntriesByName.values.toList(),
            rootEntriesComplete = snapshot.rootEntriesComplete,
            sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
            pendingAudioEntries = snapshot.pendingAudioEntries,
            pendingMetadataByAudioName = pendingMetadataByAudioName
        )
    }

    fun applyStoredEntryWrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedEntry: ManagedDownloadStorage.StoredEntry,
        bucket: ManagedDownloadStorage.SnapshotEntryBucket
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return when (bucket) {
            ManagedDownloadStorage.SnapshotEntryBucket.AUDIO -> compose(
                audioEntries = if (storedEntry.isPendingAudioWrite) {
                    snapshot.audioEntries
                } else {
                    replaceStoredEntry(snapshot.audioEntries, storedEntry)
                },
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = snapshot.lyricEntriesByName.values.toList(),
                rootEntriesComplete = snapshot.rootEntriesComplete,
                sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
                pendingAudioEntries = if (storedEntry.isPendingAudioWrite) {
                    replaceStoredEntry(snapshot.pendingAudioEntries, storedEntry)
                } else {
                    snapshot.pendingAudioEntries.filterNot { pending ->
                        pending.logicalName == storedEntry.name ||
                        pending.reference == storedEntry.reference
                    }
                },
                pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
            )

            ManagedDownloadStorage.SnapshotEntryBucket.COVER -> compose(
                audioEntries = snapshot.audioEntries,
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = replaceStoredEntry(snapshot.coverEntriesByName.values, storedEntry),
                lyricEntries = snapshot.lyricEntriesByName.values.toList(),
                rootEntriesComplete = snapshot.rootEntriesComplete,
                sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
                pendingAudioEntries = snapshot.pendingAudioEntries,
                pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
            )

            ManagedDownloadStorage.SnapshotEntryBucket.LYRIC -> compose(
                audioEntries = snapshot.audioEntries,
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = replaceStoredEntry(snapshot.lyricEntriesByName.values, storedEntry),
                rootEntriesComplete = snapshot.rootEntriesComplete,
                sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
                pendingAudioEntries = snapshot.pendingAudioEntries,
                pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
            )
        }
    }

    fun applySidecarRefresh(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        lyricEntries: List<ManagedDownloadStorage.StoredEntry>
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        if (
            snapshot.sidecarEntriesComplete &&
            snapshot.coverEntriesByName.values.toList() == coverEntries &&
            snapshot.lyricEntriesByName.values.toList() == lyricEntries
        ) {
            return snapshot
        }
        return compose(
            audioEntries = snapshot.audioEntries,
            metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
            metadataByAudioName = snapshot.metadataByAudioName,
            coverEntries = coverEntries,
            lyricEntries = lyricEntries,
            rootEntriesComplete = snapshot.rootEntriesComplete,
            sidecarEntriesComplete = true,
            pendingAudioEntries = snapshot.pendingAudioEntries,
            pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
        )
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
            .mapNotNullTo(linkedSetOf()) { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
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
                .filterNot { entry -> entry.reference in references },
            rootEntriesComplete = snapshot.rootEntriesComplete,
            sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
            pendingAudioEntries = snapshot.pendingAudioEntries
                .filterNot { entry -> entry.reference in references },
            pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
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
