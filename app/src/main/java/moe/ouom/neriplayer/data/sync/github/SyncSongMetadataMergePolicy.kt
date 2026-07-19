package moe.ouom.neriplayer.data.sync.github

internal object SyncSongMetadataMergePolicy {
    fun selectDeterministicPayload(candidates: List<SyncSong>): SyncSong {
        return candidates.maxWith(
            compareBy<SyncSong> { it.syncMetadataVersion }
                .thenBy(::canonicalPayloadKey)
        )
    }

    fun resolveSelectedPayload(
        selected: SyncSong,
        candidates: List<SyncSong>
    ): SyncSong {
        if (selected.syncMetadataVersion >= CURRENT_SYNC_METADATA_VERSION) {
            return selected
        }

        val currentPayload = candidates
            .asSequence()
            .filter { it.syncMetadataVersion >= CURRENT_SYNC_METADATA_VERSION }
            .maxByOrNull(::canonicalPayloadKey)
        if (currentPayload != null) {
            return currentPayload
        }

        return fillMissingLegacyMetadata(selected, candidates)
    }

    fun resolveAddedAt(selectedAddedAt: Long, candidates: List<SyncSong>): Long {
        if (selectedAddedAt > 0L) return selectedAddedAt
        return candidates.maxOfOrNull(SyncSong::addedAt)?.coerceAtLeast(0L) ?: 0L
    }

    fun canonicalPayloadKey(song: SyncSong): String {
        return listOf(
            song.id.toString(),
            song.name,
            song.artist,
            song.album,
            song.albumId.toString(),
            song.durationMs.toString(),
            song.coverUrl.orEmpty(),
            song.mediaUri.orEmpty(),
            song.addedAt.toString(),
            song.matchedLyric.orEmpty(),
            song.matchedTranslatedLyric.orEmpty(),
            song.matchedLyricSource.orEmpty(),
            song.matchedSongId.orEmpty(),
            song.userLyricOffsetMs.toString(),
            song.customCoverUrl.orEmpty(),
            song.customName.orEmpty(),
            song.customArtist.orEmpty(),
            song.originalName.orEmpty(),
            song.originalArtist.orEmpty(),
            song.originalCoverUrl.orEmpty(),
            song.originalLyric.orEmpty(),
            song.originalTranslatedLyric.orEmpty(),
            song.channelId.orEmpty(),
            song.audioId.orEmpty(),
            song.subAudioId.orEmpty(),
            song.playlistContextId.orEmpty(),
            song.syncMetadataVersion.toString()
        ).joinToString(separator = "") { value -> "${value.length}:$value" }
    }

    private fun fillMissingLegacyMetadata(
        selected: SyncSong,
        candidates: List<SyncSong>
    ): SyncSong {
        return selected.copy(
            id = selected.id.takeIf { it != 0L }
                ?: candidates.firstNonZero(SyncSong::id),
            name = selected.name.ifBlank { candidates.firstNonBlank(SyncSong::name) },
            artist = selected.artist.ifBlank { candidates.firstNonBlank(SyncSong::artist) },
            album = selected.album.ifBlank { candidates.firstNonBlank(SyncSong::album) },
            albumId = selected.albumId.takeIf { it != 0L }
                ?: candidates.firstNonZero(SyncSong::albumId),
            durationMs = selected.durationMs.takeIf { it > 0L }
                ?: candidates.firstPositive(SyncSong::durationMs),
            coverUrl = selected.coverUrl.nonBlankOrElse(candidates, SyncSong::coverUrl),
            mediaUri = selected.mediaUri.nonBlankOrElse(candidates, SyncSong::mediaUri),
            matchedLyric = selected.matchedLyric.nonBlankOrElse(candidates, SyncSong::matchedLyric),
            matchedTranslatedLyric = selected.matchedTranslatedLyric.nonBlankOrElse(
                candidates,
                SyncSong::matchedTranslatedLyric
            ),
            matchedLyricSource = selected.matchedLyricSource.nonBlankOrElse(
                candidates,
                SyncSong::matchedLyricSource
            ),
            matchedSongId = selected.matchedSongId.nonBlankOrElse(
                candidates,
                SyncSong::matchedSongId
            ),
            userLyricOffsetMs = selected.userLyricOffsetMs.takeIf { it != 0L }
                ?: candidates.firstNonZero(SyncSong::userLyricOffsetMs),
            customCoverUrl = selected.customCoverUrl.nonBlankOrElse(
                candidates,
                SyncSong::customCoverUrl
            ),
            customName = selected.customName.nonBlankOrElse(candidates, SyncSong::customName),
            customArtist = selected.customArtist.nonBlankOrElse(
                candidates,
                SyncSong::customArtist
            ),
            originalName = selected.originalName.nonBlankOrElse(
                candidates,
                SyncSong::originalName
            ),
            originalArtist = selected.originalArtist.nonBlankOrElse(
                candidates,
                SyncSong::originalArtist
            ),
            originalCoverUrl = selected.originalCoverUrl.nonBlankOrElse(
                candidates,
                SyncSong::originalCoverUrl
            ),
            originalLyric = selected.originalLyric.nonBlankOrElse(
                candidates,
                SyncSong::originalLyric
            ),
            originalTranslatedLyric = selected.originalTranslatedLyric.nonBlankOrElse(
                candidates,
                SyncSong::originalTranslatedLyric
            ),
            channelId = selected.channelId.nonBlankOrElse(candidates, SyncSong::channelId),
            audioId = selected.audioId.nonBlankOrElse(candidates, SyncSong::audioId),
            subAudioId = selected.subAudioId.nonBlankOrElse(candidates, SyncSong::subAudioId),
            playlistContextId = selected.playlistContextId.nonBlankOrElse(
                candidates,
                SyncSong::playlistContextId
            )
        )
    }

    private fun List<SyncSong>.firstNonBlank(selector: (SyncSong) -> String): String {
        return firstNotNullOfOrNull { song -> selector(song).takeIf(String::isNotBlank) }.orEmpty()
    }

    private fun List<SyncSong>.firstNonZero(selector: (SyncSong) -> Long): Long {
        return firstNotNullOfOrNull { song -> selector(song).takeIf { it != 0L } } ?: 0L
    }

    private fun List<SyncSong>.firstPositive(selector: (SyncSong) -> Long): Long {
        return firstNotNullOfOrNull { song -> selector(song).takeIf { it > 0L } } ?: 0L
    }

    private fun String?.nonBlankOrElse(
        candidates: List<SyncSong>,
        selector: (SyncSong) -> String?
    ): String? {
        if (!isNullOrBlank()) return this
        return candidates.firstNotNullOfOrNull { song ->
            selector(song)?.takeIf(String::isNotBlank)
        }
    }
}
