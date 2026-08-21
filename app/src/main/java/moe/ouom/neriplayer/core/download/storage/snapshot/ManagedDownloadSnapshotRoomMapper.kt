package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotEntryEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotMetadataEntity

internal object ManagedDownloadSnapshotRoomMapper {
    const val BUCKET_AUDIO = "audio"
    const val BUCKET_METADATA = "metadata"
    const val BUCKET_COVER = "cover"
    const val BUCKET_LYRIC = "lyric"

    fun toEntryEntities(
        rootKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<DownloadSnapshotEntryEntity> {
        return buildList {
            addAll(snapshot.audioEntries.toEntryEntities(rootKey, BUCKET_AUDIO))
            addAll(
                snapshot.metadataEntriesByAudioName.values
                    .toList()
                    .toEntryEntities(rootKey, BUCKET_METADATA)
            )
            addAll(
                snapshot.coverEntriesByName.values
                    .toList()
                    .toEntryEntities(rootKey, BUCKET_COVER)
            )
            addAll(
                snapshot.lyricEntriesByName.values
                    .toList()
                    .toEntryEntities(rootKey, BUCKET_LYRIC)
            )
        }
    }

    fun toMetadataEntities(
        rootKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<DownloadSnapshotMetadataEntity> {
        return snapshot.metadataByAudioName
            .entries
            .sortedBy { it.key }
            .map { (audioName, metadata) ->
                DownloadSnapshotMetadataEntity(
                    rootKey = rootKey,
                    audioName = audioName,
                    stableKey = metadata.stableKey,
                    songId = metadata.songId,
                    identityAlbum = metadata.identityAlbum,
                    album = metadata.album,
                    name = metadata.name,
                    artist = metadata.artist,
                    coverUrl = metadata.coverUrl,
                    matchedLyric = metadata.matchedLyric,
                    matchedTranslatedLyric = metadata.matchedTranslatedLyric,
                    matchedRomanizedLyric = metadata.matchedRomanizedLyric,
                    matchedLyricSource = metadata.matchedLyricSource,
                    matchedSongId = metadata.matchedSongId,
                    userLyricOffsetMs = metadata.userLyricOffsetMs,
                    customCoverUrl = metadata.customCoverUrl,
                    customName = metadata.customName,
                    customArtist = metadata.customArtist,
                    originalName = metadata.originalName,
                    originalArtist = metadata.originalArtist,
                    originalCoverUrl = metadata.originalCoverUrl,
                    originalLyric = metadata.originalLyric,
                    originalTranslatedLyric = metadata.originalTranslatedLyric,
                    originalRomanizedLyric = metadata.originalRomanizedLyric,
                    mediaUri = metadata.mediaUri,
                    channelId = metadata.channelId,
                    audioId = metadata.audioId,
                    subAudioId = metadata.subAudioId,
                    playlistContextId = metadata.playlistContextId,
                    coverPath = metadata.coverPath,
                    lyricPath = metadata.lyricPath,
                    translatedLyricPath = metadata.translatedLyricPath,
                    romanizedLyricPath = metadata.romanizedLyricPath,
                    durationMs = metadata.durationMs,
                    downloadFinalized = metadata.downloadFinalized,
                    createdAtMs = metadata.createdAtMs,
                    createdAtSource = metadata.createdAtSource
                )
            }
    }

    fun toSnapshot(
        audioEntries: List<DownloadSnapshotEntryEntity>,
        metadataEntries: List<DownloadSnapshotEntryEntity>,
        metadata: List<DownloadSnapshotMetadataEntity>,
        coverEntries: List<DownloadSnapshotEntryEntity>,
        lyricEntries: List<DownloadSnapshotEntryEntity>
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries.map { it.toStoredEntry() },
            metadataEntries = metadataEntries.map { it.toStoredEntry() },
            metadataByAudioName = metadata.associate { entity ->
                entity.audioName to entity.toDownloadedAudioMetadata()
            },
            coverEntries = coverEntries.map { it.toStoredEntry() },
            lyricEntries = lyricEntries.map { it.toStoredEntry() }
        )
    }

    private fun List<ManagedDownloadStorage.StoredEntry>.toEntryEntities(
        rootKey: String,
        bucket: String
    ): List<DownloadSnapshotEntryEntity> {
        return distinctBy(::entryKey)
            .mapIndexed { index, entry ->
                DownloadSnapshotEntryEntity(
                    rootKey = rootKey,
                    bucket = bucket,
                    entryKey = entryKey(entry),
                    displayPosition = index,
                    name = entry.name,
                    reference = entry.reference,
                    mediaUri = entry.mediaUri,
                    localFilePath = entry.localFilePath,
                    sizeBytes = entry.sizeBytes,
                    lastModifiedMs = entry.lastModifiedMs,
                    isDirectory = entry.isDirectory
                )
            }
    }

    private fun DownloadSnapshotEntryEntity.toStoredEntry(): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = localFilePath,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory
        )
    }

    private fun DownloadSnapshotMetadataEntity.toDownloadedAudioMetadata(): ManagedDownloadStorage.DownloadedAudioMetadata {
        return ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = stableKey,
            songId = songId,
            identityAlbum = identityAlbum,
            album = album,
            name = name,
            artist = artist,
            coverUrl = coverUrl,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedRomanizedLyric = matchedRomanizedLyric,
            matchedLyricSource = matchedLyricSource,
            matchedSongId = matchedSongId,
            userLyricOffsetMs = userLyricOffsetMs,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            originalRomanizedLyric = originalRomanizedLyric,
            mediaUri = mediaUri,
            channelId = channelId,
            audioId = audioId,
            subAudioId = subAudioId,
            playlistContextId = playlistContextId,
            coverPath = coverPath,
            lyricPath = lyricPath,
            translatedLyricPath = translatedLyricPath,
            romanizedLyricPath = romanizedLyricPath,
            durationMs = durationMs,
            downloadFinalized = downloadFinalized,
            createdAtMs = createdAtMs,
            createdAtSource = createdAtSource
        )
    }

    private fun entryKey(entry: ManagedDownloadStorage.StoredEntry): String {
        entry.reference.takeIf(String::isNotBlank)?.let { return it }
        entry.mediaUri.takeIf(String::isNotBlank)?.let { return "uri:$it" }
        return "name:${entry.name}"
    }
}
