package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.store.stats.toDomain


import moe.ouom.neriplayer.data.model.lyrics.LyricSyncPersistence
import moe.ouom.neriplayer.data.model.lyrics.readLyricSyncPersistence
import moe.ouom.neriplayer.data.model.lyrics.toPersistenceJson
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.local.database.entity.PlayHistoryEntity
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey

internal class PlayHistoryRoomMapper {
    fun toEntities(entries: List<PlayedEntry>): List<PlayHistoryEntity> {
        return entries.map(PlayedEntry::toEntity)
    }

    fun toDomain(entries: List<PlayHistoryEntity>): List<PlayedEntry> {
        return entries
            .sortedWith(compareByDescending<PlayHistoryEntity> { it.playedAt }.thenBy { it.identityKey })
            .map { it.toDomain() }
    }

    fun validateRoundTrip(entries: List<PlayedEntry>): Boolean {
        val normalized = entries.sortedWith(
            compareByDescending<PlayedEntry> { it.playedAt }.thenBy {
                SongIdentity(it.id, it.album, it.localFilePath ?: it.mediaUri).stableKey()
            }
        )
        return toDomain(toEntities(entries)) == normalized
    }

    private fun PlayHistoryEntity.toDomain(): PlayedEntry {
        val expectedIdentity = SongIdentity(
            id = identityId,
            album = identityAlbum,
            mediaUri = identityMediaUri
        ).stableKey()
        check(expectedIdentity == identityKey) {
            "Play history identity mismatch: expected=$expectedIdentity actual=$identityKey"
        }
        val lyrics = readLyricSyncPersistence(lyricSyncPayloadJson)
        return PlayedEntry(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            resumePositionMs = resumePositionMs,
            coverUrl = coverUrl,
            mediaUri = mediaUri,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedRomanizedLyric = lyrics.romanized,
            originalRomanizedLyric = lyrics.originalRomanized,
            matchedLyricSource = lyrics.source,
            matchedSongId = lyrics.matchedSongId,
            userLyricOffsetMs = lyrics.userOffsetMs,
            lyricSyncRevision = lyrics.revision,
            lyricSyncEdited = lyrics.edited,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            localFileName = localFileName,
            localFilePath = localFilePath,
            channelId = channelId,
            audioId = audioId,
            subAudioId = subAudioId,
            sourceStableKey = sourceStableKey,
            playedAt = playedAt
        )
    }
}

private fun PlayedEntry.toEntity(): PlayHistoryEntity {
    val identity = SongIdentity(
        id = id,
        album = album,
        mediaUri = localFilePath ?: mediaUri
    )
    return PlayHistoryEntity(
        identityKey = identity.stableKey(),
        identityId = identity.id,
        identityAlbum = identity.album,
        identityMediaUri = identity.mediaUri,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        resumePositionMs = resumePositionMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        sourceStableKey = sourceStableKey,
        playedAt = playedAt,
        lyricSyncPayloadJson = LyricSyncPersistence(
            revision = lyricSyncRevision, edited = lyricSyncEdited,
            romanized = matchedRomanizedLyric, originalRomanized = originalRomanizedLyric,
            source = matchedLyricSource, matchedSongId = matchedSongId, userOffsetMs = userLyricOffsetMs
        ).toPersistenceJson()
    )
}
