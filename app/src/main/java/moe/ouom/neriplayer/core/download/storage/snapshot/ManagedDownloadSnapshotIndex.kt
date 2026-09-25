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
            emptyMap(),
        rootEmptyConfirmationPending: Boolean = false
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val normalizedPendingAudioEntries = (pendingAudioEntries + audioEntries.filter {
            it.isPendingAudioWrite
        }).distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        val pendingAudioNames = normalizedPendingAudioEntries
            .mapTo(hashSetOf()) { entry ->
                ManagedDownloadTreeNaming.canonicalLookupName(entry.logicalName)
            }
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
                ) || ManagedDownloadTreeNaming.canonicalLookupName(audioName) in
                pendingAudioNames
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
        val metadataGroupsByAudioName = metadataEntriesWithAudioNames.groupBy { (audioName, _) ->
            ManagedDownloadTreeNaming.canonicalLookupName(audioName)
        }
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
            .filterKeys { audioName ->
                ManagedDownloadTreeNaming.canonicalLookupName(audioName) in pendingAudioNames
            }
        val pendingMetadataNames = pendingMetadataNamesFromEntries +
            normalizedPendingMetadataByAudioName.keys.map {
                ManagedDownloadTreeNaming.canonicalLookupName(it)
            }
        val normalizedMetadataByAudioName = metadataByAudioName.filterKeys { audioName ->
            val canonicalAudioName = ManagedDownloadTreeNaming.canonicalLookupName(audioName)
            canonicalAudioName !in pendingMetadataNames || canonicalAudioName in pendingAudioNames
        }
        val coverEntriesByName = coverEntries.associateBy(ManagedDownloadStorage.StoredEntry::name)
        val lyricEntriesByName = lyricEntries.associateBy(ManagedDownloadStorage.StoredEntry::name)
        val audioEntriesByStableKey = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesBySongId = mutableMapOf<Long, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesByMediaUri = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesByRemoteTrackKey = mutableMapOf<String, MutableList<ManagedDownloadStorage.StoredEntry>>()
        val audioEntriesWithoutMetadata = mutableListOf<ManagedDownloadStorage.StoredEntry>()
        val metadataLookupIndex = buildMetadataLookupIndex(normalizedMetadataByAudioName)

        normalizedAudioEntries.forEach { entry ->
            // metadata 可能按逻辑文件名保存，索引时要同时接受两种命名
            val metadata = metadataForAudioEntry(
                metadataByAudioName = normalizedMetadataByAudioName,
                lookupIndex = metadataLookupIndex,
                audio = entry
            )
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
            rootEmptyConfirmationPending = rootEmptyConfirmationPending,
            sidecarEntriesComplete = sidecarEntriesComplete,
            pendingAudioEntries = normalizedPendingAudioEntries,
            pendingMetadataByAudioName = normalizedPendingMetadataByAudioName
        )
    }

    private fun metadataForAudioEntry(
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        lookupIndex: MetadataLookupIndex,
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        val canonicalAudioName = ManagedDownloadTreeNaming.canonicalLookupName(audio.name)
        val canonicalLogicalName = ManagedDownloadTreeNaming.canonicalLookupName(audio.logicalName)
        return metadataByAudioName[audio.name]
            ?: metadataByAudioName[audio.logicalName]
            ?: lookupIndex.byMapKey[canonicalAudioName]
            ?: lookupIndex.byMapKey[canonicalLogicalName]
            ?: lookupIndex.byDeclaredAudioName[canonicalAudioName]
            ?: lookupIndex.byDeclaredAudioName[canonicalLogicalName]
            ?: lookupIndex.byReference[audio.reference]
            ?: lookupIndex.byReference[audio.mediaUri]
            ?: audio.localFilePath?.let(lookupIndex.byReference::get)
    }

    private fun buildMetadataLookupIndex(
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    ): MetadataLookupIndex {
        val byMapKey = linkedMapOf<String, ManagedDownloadStorage.DownloadedAudioMetadata>()
        val byDeclaredAudioName = linkedMapOf<String, ManagedDownloadStorage.DownloadedAudioMetadata>()
        val byReference = linkedMapOf<String, ManagedDownloadStorage.DownloadedAudioMetadata>()
        metadataByAudioName.forEach { (audioName, metadata) ->
            byMapKey.putIfAbsent(
                ManagedDownloadTreeNaming.canonicalLookupName(audioName),
                metadata
            )
            metadata.audioFileName
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(ManagedDownloadTreeNaming::canonicalLookupName)
                ?.let { key -> byDeclaredAudioName.putIfAbsent(key, metadata) }
            metadata.mediaUri
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { reference -> byReference.putIfAbsent(reference, metadata) }
        }
        return MetadataLookupIndex(
            byMapKey = byMapKey,
            byDeclaredAudioName = byDeclaredAudioName,
            byReference = byReference
        )
    }

    private data class MetadataLookupIndex(
        val byMapKey: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        val byDeclaredAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        val byReference: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    )

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
        val metadataByAudioName = if (isPendingMetadataWrite && hasCanonicalMetadata) {
            snapshot.metadataByAudioName
        } else {
            snapshot.metadataByAudioName.toMutableMap().apply {
                put(targetAudioName, metadata)
            }
        }
        val canonicalTargetAudioName = ManagedDownloadTreeNaming.canonicalLookupName(targetAudioName)
        val hasExactPendingAudio = snapshot.pendingAudioEntries.any { pending ->
            pending.logicalName == targetAudioName
        }
        val existingIndexedMetadata = snapshot.metadataByAudioName[targetAudioName]
            ?: if (hasExactPendingAudio) {
                null
            } else {
                snapshot.metadataByCanonicalAudioName[canonicalTargetAudioName]
            }
        val effectiveMetadataUnchanged = isPendingMetadataWrite && hasCanonicalMetadata ||
            existingIndexedMetadata?.hasSameAudioIndexIdentity(metadata) == true
        val matchingAudioExists = snapshot.audioEntries.any { audio ->
            ManagedDownloadTreeNaming.canonicalLookupName(audio.name) == canonicalTargetAudioName ||
                ManagedDownloadTreeNaming.canonicalLookupName(audio.logicalName) == canonicalTargetAudioName
        } || metadata.mediaUri
            ?.takeIf(String::isNotBlank)
            ?.let(snapshot.audioEntriesByLookupKey::containsKey) == true
        if (effectiveMetadataUnchanged || existingIndexedMetadata == null && !matchingAudioExists) {
            val metadataEntriesByAudioName = if (isPendingMetadataWrite && hasCanonicalMetadata) {
                snapshot.metadataEntriesByAudioName
            } else {
                snapshot.metadataEntriesByAudioName.toMutableMap().apply {
                    entries.removeAll { (_, entry) ->
                        ManagedDownloadTreeNaming.metadataAudioName(entry.name) == targetAudioName
                    }
                    put(targetAudioName, metadataEntry)
                }
            }
            val retainedMetadataReferences = metadataEntriesByAudioName.values
                .mapTo(hashSetOf(), ManagedDownloadStorage.StoredEntry::reference)
            val removedMetadataReferences = snapshot.metadataEntriesByAudioName.values
                .asSequence()
                .filter { entry -> entry.reference !in retainedMetadataReferences }
                .mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::reference)
            val knownReferences = updateKnownReferences(
                snapshot = snapshot,
                removedReferences = removedMetadataReferences,
                addedReferences = if (metadataEntry in metadataEntriesByAudioName.values) {
                    setOf(metadataEntry.reference)
                } else {
                    emptySet()
                },
                metadataEntriesByAudioName = metadataEntriesByAudioName
            )
            return snapshot.copy(
                metadataEntriesByAudioName = metadataEntriesByAudioName,
                metadataByAudioName = metadataByAudioName,
                pendingMetadataByAudioName = pendingMetadataByAudioName,
                knownReferences = knownReferences
            )
        }
        return compose(
            audioEntries = snapshot.audioEntries,
            metadataEntries = metadataEntries,
            metadataByAudioName = metadataByAudioName,
            coverEntries = snapshot.coverEntriesByName.values.toList(),
            lyricEntries = snapshot.lyricEntriesByName.values.toList(),
            rootEntriesComplete = snapshot.rootEntriesComplete,
            rootEmptyConfirmationPending = snapshot.rootEmptyConfirmationPending,
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
            ManagedDownloadStorage.SnapshotEntryBucket.AUDIO ->
                applyAudioEntryWrite(snapshot, storedEntry)

            ManagedDownloadStorage.SnapshotEntryBucket.COVER,
            ManagedDownloadStorage.SnapshotEntryBucket.LYRIC ->
                applySidecarWrite(snapshot, storedEntry, bucket)
        }
    }

    private fun applySidecarWrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        entry: ManagedDownloadStorage.StoredEntry,
        bucket: ManagedDownloadStorage.SnapshotEntryBucket
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val covers = bucket == ManagedDownloadStorage.SnapshotEntryBucket.COVER
        val entries = if (covers) snapshot.coverEntriesByName else snapshot.lyricEntriesByName
        val replaced = entries.values.filter { it.name == entry.name || it.reference == entry.reference }
        val updatedEntries = entries.toMutableMap().apply {
            replaced.forEach { remove(it.name) }
            put(entry.name, entry)
        }
        val removedReferences = replaced.mapTo(hashSetOf()) { it.reference }.apply {
            remove(entry.reference)
        }
        // 替换旧引用时保留其它桶仍在使用的引用，新建侧载不需要遍历音频或元信息
        if (removedReferences.isNotEmpty()) {
            val otherSidecars = if (covers) snapshot.lyricEntriesByName else snapshot.coverEntriesByName
            removedReferences.removeAll { reference ->
                reference in snapshot.audioEntriesByLookupKey ||
                    snapshot.pendingAudioEntries.any { it.reference == reference } ||
                    snapshot.metadataEntriesByAudioName.values.any { it.reference == reference } ||
                    otherSidecars.values.any { it.reference == reference }
            }
        }
        return snapshot.copy(
            coverEntriesByName = if (covers) updatedEntries else snapshot.coverEntriesByName,
            lyricEntriesByName = if (covers) snapshot.lyricEntriesByName else updatedEntries,
            knownReferences = (snapshot.knownReferences - removedReferences) + entry.reference
        )
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
            rootEmptyConfirmationPending = snapshot.rootEmptyConfirmationPending,
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
        fun ManagedDownloadStorage.StoredEntry.matchesDeletedReference(): Boolean {
            return reference in references || mediaUri in references || localFilePath in references
        }
        val affectsIndexedEntry = snapshot.audioEntries.any {
            it.matchesDeletedReference()
        } || snapshot.pendingAudioEntries.any {
            it.matchesDeletedReference()
        } || snapshot.metadataEntriesByAudioName.values.any { it.reference in references } ||
            snapshot.coverEntriesByName.values.any { it.reference in references } ||
            snapshot.lyricEntriesByName.values.any { it.reference in references }
        if (!affectsIndexedEntry) {
            return snapshot.copy(
                knownReferences = snapshot.knownReferences - references
            )
        }
        val deletedMetadataAudioNames = snapshot.metadataEntriesByAudioName.values
            .filter { entry -> entry.reference in references }
            .mapNotNullTo(linkedSetOf()) { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
        return compose(
            audioEntries = snapshot.audioEntries.filterNot {
                it.matchesDeletedReference()
            },
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
            rootEmptyConfirmationPending = snapshot.rootEmptyConfirmationPending,
            sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
            pendingAudioEntries = snapshot.pendingAudioEntries.filterNot {
                it.matchesDeletedReference()
            },
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

    private fun applyAudioEntryWrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedEntry: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        if (storedEntry.isPendingAudioWrite) {
            val replaced = snapshot.pendingAudioEntries.filter { pending ->
                pending.reference == storedEntry.reference || pending.name == storedEntry.name
            }
            val pendingAudioEntries = replaceStoredEntry(snapshot.pendingAudioEntries, storedEntry)
            return snapshot.copy(
                pendingAudioEntries = pendingAudioEntries,
                knownReferences = updateKnownReferences(
                    snapshot = snapshot,
                    removedReferences = replaced.mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::reference),
                    addedReferences = setOf(storedEntry.reference),
                    pendingAudioEntries = pendingAudioEntries
                )
            )
        }

        val existing = snapshot.audioEntriesByLookupKey[storedEntry.reference]
            ?: snapshot.audioEntriesByLookupKey[storedEntry.mediaUri]
            ?: storedEntry.localFilePath?.let(snapshot.audioEntriesByLookupKey::get)
            ?: snapshot.audioEntries.firstOrNull { audio ->
                audio.name == storedEntry.name || audio.logicalName == storedEntry.logicalName
            }
        if (existing != null) {
            return compose(
                audioEntries = replaceStoredEntry(snapshot.audioEntries, storedEntry),
                metadataEntries = snapshot.metadataEntriesByAudioName.values.toList(),
                metadataByAudioName = snapshot.metadataByAudioName,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = snapshot.lyricEntriesByName.values.toList(),
                rootEntriesComplete = snapshot.rootEntriesComplete,
                rootEmptyConfirmationPending = snapshot.rootEmptyConfirmationPending,
                sidecarEntriesComplete = snapshot.sidecarEntriesComplete,
                pendingAudioEntries = snapshot.pendingAudioEntries.filterNot { pending ->
                    pending.logicalName == storedEntry.name || pending.reference == storedEntry.reference
                },
                pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName
            )
        }

        val removedPending = snapshot.pendingAudioEntries.filter { pending ->
            pending.logicalName == storedEntry.name || pending.reference == storedEntry.reference
        }
        val pendingAudioEntries = snapshot.pendingAudioEntries - removedPending.toSet()
        val pendingNames = pendingAudioEntries.mapTo(hashSetOf()) { pending ->
            ManagedDownloadTreeNaming.canonicalLookupName(pending.logicalName)
        }
        val pendingMetadataByAudioName = snapshot.pendingMetadataByAudioName.filterKeys { audioName ->
            ManagedDownloadTreeNaming.canonicalLookupName(audioName) in pendingNames
        }
        val metadata = snapshot.metadataByAudioName[storedEntry.name]
            ?: snapshot.metadataByAudioName[storedEntry.logicalName]
            ?: snapshot.metadataByCanonicalAudioName[
                ManagedDownloadTreeNaming.canonicalLookupName(storedEntry.name)
            ]
        val audioEntriesByLookupKey = snapshot.audioEntriesByLookupKey.toMutableMap().apply {
            put(storedEntry.reference, storedEntry)
            put(storedEntry.mediaUri, storedEntry)
            storedEntry.localFilePath?.let { put(it, storedEntry) }
        }
        val audioEntriesWithoutMetadata = if (metadata == null) {
            snapshot.audioEntriesWithoutMetadata + storedEntry
        } else {
            snapshot.audioEntriesWithoutMetadata
        }
        return snapshot.copy(
            audioEntries = snapshot.audioEntries + storedEntry,
            audioEntriesByLookupKey = audioEntriesByLookupKey,
            audioEntriesWithoutMetadata = audioEntriesWithoutMetadata,
            audioEntriesByStableKey = addAudioIndexEntry(
                snapshot.audioEntriesByStableKey,
                metadata?.stableKey,
                storedEntry
            ),
            audioEntriesBySongId = addAudioIndexEntry(
                snapshot.audioEntriesBySongId,
                metadata?.songId?.takeIf { it > 0L },
                storedEntry
            ),
            audioEntriesByMediaUri = addAudioIndexEntry(
                snapshot.audioEntriesByMediaUri,
                metadata?.mediaUri?.takeIf(String::isNotBlank),
                storedEntry
            ),
            audioEntriesByRemoteTrackKey = addAudioIndexEntry(
                snapshot.audioEntriesByRemoteTrackKey,
                metadata?.let {
                    buildRemoteTrackKey(it.channelId, it.audioId, it.subAudioId)
                },
                storedEntry
            ),
            pendingAudioEntries = pendingAudioEntries,
            pendingMetadataByAudioName = pendingMetadataByAudioName,
            knownReferences = updateKnownReferences(
                snapshot = snapshot,
                removedReferences = removedPending.mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::reference),
                addedReferences = setOf(storedEntry.reference),
                pendingAudioEntries = pendingAudioEntries
            )
        )
    }

    private fun <K> addAudioIndexEntry(
        index: Map<K, List<ManagedDownloadStorage.StoredEntry>>,
        key: K?,
        entry: ManagedDownloadStorage.StoredEntry
    ): Map<K, List<ManagedDownloadStorage.StoredEntry>> {
        if (key == null) return index
        return index.toMutableMap().apply {
            put(key, index[key].orEmpty() + entry)
        }
    }

    private fun ManagedDownloadStorage.DownloadedAudioMetadata.hasSameAudioIndexIdentity(
        other: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        return stableKey == other.stableKey &&
            songId == other.songId &&
            mediaUri == other.mediaUri &&
            channelId == other.channelId &&
            audioId == other.audioId &&
            subAudioId == other.subAudioId &&
            audioFileName == other.audioFileName
    }

    private fun updateKnownReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        removedReferences: Set<String>,
        addedReferences: Set<String>,
        metadataEntriesByAudioName: Map<String, ManagedDownloadStorage.StoredEntry> =
            snapshot.metadataEntriesByAudioName,
        pendingAudioEntries: List<ManagedDownloadStorage.StoredEntry> = snapshot.pendingAudioEntries
    ): Set<String> {
        if (removedReferences.isEmpty() && addedReferences.isEmpty()) {
            return snapshot.knownReferences
        }
        return snapshot.knownReferences.toMutableSet().apply {
            removedReferences.forEach { reference ->
                val stillUsed = reference in snapshot.audioEntriesByLookupKey ||
                    pendingAudioEntries.any { it.reference == reference } ||
                    metadataEntriesByAudioName.values.any { it.reference == reference } ||
                    snapshot.coverEntriesByName.values.any { it.reference == reference } ||
                    snapshot.lyricEntriesByName.values.any { it.reference == reference }
                if (!stillUsed) remove(reference)
            }
            addAll(addedReferences)
        }
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
