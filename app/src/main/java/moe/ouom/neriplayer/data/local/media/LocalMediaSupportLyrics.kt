package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ResolvedInspectableLocalMedia
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.EditableMetadataWriteTransaction
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.EditableCoverWritePlan
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.EditableMetadataSnapshot
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal fun LocalMediaSupport.inspectLyricsFromDirectFile(
    file: File
): DirectLocalLyricsInspection {
    val metadataFile = File(
        file.parentFile ?: return DirectLocalLyricsInspection(
            original = null,
            translated = null,
            romanized = null,
            metadataOriginal = null,
            metadataTranslated = null,
            metadataRomanized = null,
            hasOriginalSidecar = false,
            hasTranslatedSidecar = false,
            hasRomanizedSidecar = false
        ),
        file.name + LOCAL_METADATA_SUFFIX
    )
    val localMetadata = if (metadataFile.isFile) {
        readTextFile(metadataFile)?.let {
            parseLocalMetadataSidecar(metadataFile.absolutePath, it)
        }
    } else {
        null
    }
    val nearbyFiles = findNearbyLyricFiles(file)
    fun read(reference: File?): String? {
        return reference?.let(::readTextFile)
    }
    val nearbyLyric = read(nearbyFiles.original)
    val nearbyTranslatedLyric = read(nearbyFiles.translated)
    val nearbyRomanizedLyric = read(nearbyFiles.romanized)
    return DirectLocalLyricsInspection(
        original = nearbyLyric,
        translated = nearbyTranslatedLyric,
        romanized = nearbyRomanizedLyric,
        metadataOriginal = localMetadata?.takeIf { it.hasLyricOverride }?.lyric,
        metadataTranslated = localMetadata
            ?.takeIf { it.hasTranslatedLyricOverride }
            ?.translatedLyric,
        metadataRomanized = localMetadata
            ?.takeIf { it.hasRomanizedLyricOverride }
            ?.romanizedLyric,
        hasOriginalSidecar = nearbyFiles.original != null && nearbyLyric != null,
        hasTranslatedSidecar = nearbyFiles.translated != null && nearbyTranslatedLyric != null,
        hasRomanizedSidecar = nearbyFiles.romanized != null && nearbyRomanizedLyric != null
    )
}

internal fun LocalMediaSupport.mergeLyricsInspections(
    primary: DirectLocalLyricsInspection?,
    fallback: DirectLocalLyricsInspection?
): DirectLocalLyricsInspection? {
    if (primary == null) return fallback
    if (fallback == null) return primary
    return DirectLocalLyricsInspection(
        original = if (primary.hasOriginalSidecar) primary.original else fallback.original,
        translated = if (primary.hasTranslatedSidecar) {
            primary.translated
        } else {
            fallback.translated
        },
        romanized = if (primary.hasRomanizedSidecar) {
            primary.romanized
        } else {
            fallback.romanized
        },
        metadataOriginal = primary.metadataOriginal ?: fallback.metadataOriginal,
        metadataTranslated = primary.metadataTranslated ?: fallback.metadataTranslated,
        metadataRomanized = primary.metadataRomanized ?: fallback.metadataRomanized,
        hasOriginalSidecar = primary.hasOriginalSidecar || fallback.hasOriginalSidecar,
        hasTranslatedSidecar = primary.hasTranslatedSidecar || fallback.hasTranslatedSidecar,
        hasRomanizedSidecar = primary.hasRomanizedSidecar || fallback.hasRomanizedSidecar
    )
}

internal fun LocalMediaSupport.inspectLyricsFromContentUri(
    context: Context,
    sourceUri: Uri,
    displayName: String
): DirectLocalLyricsInspection {
    val references = resolveContentSidecarReferences(
        context = context,
        sourceUri = sourceUri,
        displayName = displayName
    )
    val metadata = references.metadataReference
        ?.takeUnless(::isMediaStoreSidecarReference)
        ?.let { reference ->
            readTextContent(context, reference)?.let { raw ->
                parseLocalMetadataSidecar(reference, raw)
            }
        }
    fun read(reference: String?): String? {
        return reference
            ?.takeUnless(::isMediaStoreSidecarReference)
            ?.let { readTextContent(context, it) }
    }
    val original = read(references.lyricReferences.original)
    val translated = read(references.lyricReferences.translated)
    val romanized = read(references.lyricReferences.romanized)
    return DirectLocalLyricsInspection(
        original = original,
        translated = translated,
        romanized = romanized,
        metadataOriginal = metadata?.takeIf { it.hasLyricOverride }?.lyric,
        metadataTranslated = metadata
            ?.takeIf { it.hasTranslatedLyricOverride }
            ?.translatedLyric,
        metadataRomanized = metadata
            ?.takeIf { it.hasRomanizedLyricOverride }
            ?.romanizedLyric,
        hasOriginalSidecar = references.lyricReferences.original != null && original != null,
        hasTranslatedSidecar = references.lyricReferences.translated != null &&
            translated != null,
        hasRomanizedSidecar = references.lyricReferences.romanized != null &&
            romanized != null
    )
}

internal fun LocalMediaSupport.buildLocalLyricsCacheKey(
    song: SongItem,
    source: Uri?,
    includeEmbeddedFallback: Boolean,
    includeStoredFallback: Boolean
): String {
    val localFile = song.localFilePath?.let(::File)
    val localFileState = localFile?.let {
        "${it.length()}:${it.lastModified()}:${it.parentFile?.lastModified()}"
    }.orEmpty()
    return listOf(
        song.sourceStableKey,
        song.localFilePath,
        song.localFileName,
        source?.toString(),
        includeEmbeddedFallback,
        includeStoredFallback,
        localLyricsModelState(song),
        localFileState,
        localLyricsCacheState(localFile)
    ).joinToString("|")
}

internal fun LocalMediaSupport.localLyricsModelState(song: SongItem): String {
    return listOf(
        song.matchedLyric,
        song.matchedTranslatedLyric,
        song.matchedRomanizedLyric,
        song.originalLyric,
        song.originalTranslatedLyric,
        song.originalRomanizedLyric
    ).joinToString("|") { value ->
        value?.let { "${it.length}:${it.hashCode()}" }.orEmpty()
    }
}

internal fun LocalMediaSupport.localLyricsCacheState(localFile: File?): String {
    val actualFile = localFile ?: return ""
    val parent = actualFile.parentFile ?: return ""
    val legacyRoot = File(LEGACY_DOWNLOAD_ROOT)
    val isLegacyDownload = runCatching {
        isFileInsideDirectory(actualFile, legacyRoot)
    }.getOrDefault(false)
    val searchDirectories = buildList {
        if (isLegacyDownload) add(File(legacyRoot, "Lyrics"))
        add(File(parent, "Lyrics"))
        add(parent)
    }.distinctBy(File::getAbsolutePath)
    val files = buildList {
        add(File(parent, actualFile.name + LOCAL_METADATA_SUFFIX))
        LyricKind.entries.forEach { kind ->
            searchDirectories.forEach { directory ->
                addAll(
                    lyricSidecarNames(
                        baseName = actualFile.nameWithoutExtension,
                        kind = kind,
                        extensions = lyricExtensions
                    ).map { name -> File(directory, name) }
                )
            }
        }
    }
    return files.joinToString(",") { file ->
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
}

internal fun LocalMediaSupport.selectEditableMetadataWriteFallback(
    current: LocalMediaMetadataWriteOutcome,
    candidate: LocalMediaMetadataWriteOutcome
): LocalMediaMetadataWriteOutcome {
    return when {
        current == LocalMediaMetadataWriteOutcome.SIDECAR_ONLY ||
            candidate == LocalMediaMetadataWriteOutcome.SIDECAR_ONLY -> {
            LocalMediaMetadataWriteOutcome.SIDECAR_ONLY
        }
        current == LocalMediaMetadataWriteOutcome.FAILED ||
            candidate == LocalMediaMetadataWriteOutcome.FAILED -> LocalMediaMetadataWriteOutcome.FAILED
        current == LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE ||
            candidate == LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE -> {
            LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE
        }
        else -> LocalMediaMetadataWriteOutcome.NOT_WRITABLE
    }
}

internal fun LocalMediaSupport.resolveEditableMediaExtension(song: SongItem, sourcePathSegment: String?): String {
    return listOf(
        song.localFileName,
        song.localFilePath,
        sourcePathSegment,
        song.mediaUri
    ).firstNotNullOfOrNull { reference ->
        reference
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
    }
        ?: "bin"
}

internal fun LocalMediaSupport.writeEditableMetadataDirectTransaction(
    context: Context,
    song: SongItem,
    sourceUri: Uri,
    coverReference: String?,
    writeCover: Boolean,
    writeLyrics: Boolean,
    embeddedPropertyMapOverride: PropertyMap? = null,
    requiredEmbeddedPropertyKeys: Set<String> = emptySet()
): EditableMetadataWriteTransaction {
    val resolved = runCatching {
        resolveInspectableLocalMedia(
            context = context,
            uri = sourceUri,
            allowDescriptorFallback = true
        )
    }.getOrElse { error ->
        logEditableMetadataFailure("resolve", sourceUri, error)
        return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
    }
    val metadataSnapshot = openTagLibDescriptor(
        context = context,
        uri = sourceUri,
        file = resolved.file
    )?.use { target ->
        val existing = loadTagLibPropertyMap(target)
            ?: return@use null
        val lyrics = if (writeLyrics) {
            song.matchedLyric ?: song.originalLyric
        } else {
            null
        }
        val translatedLyrics = if (writeLyrics) {
            song.matchedTranslatedLyric ?: song.originalTranslatedLyric
        } else {
            null
        }
        val romanizedLyrics = if (writeLyrics) {
            song.matchedRomanizedLyric ?: song.originalRomanizedLyric
        } else {
            null
        }
        val updated = embeddedPropertyMapOverride?.let(::copyEditablePropertyMap)
            ?: applyEditableMetadata(
                propertyMap = existing,
                title = song.displayName(),
                artist = song.displayArtist(),
                lyrics = lyrics,
                translatedLyrics = translatedLyrics,
                romanizedLyrics = romanizedLyrics,
                audioExtension = resolved.fileExtension,
                writeLyrics = writeLyrics,
                sourceStableKey = editableMetadataSourceStableKey(song)
            )
        val picturePlan = buildEditableCoverWritePlan(
            context = context,
            descriptor = target,
            coverReference = coverReference,
            writeCover = writeCover,
            audioExtension = resolved.fileExtension
        )
        EditableMetadataSnapshot(
            existingProperties = existing,
            updatedProperties = updated,
            picturePlan = picturePlan,
            expectedStandardLyrics = mergeLyricsForExternalPlayers(lyrics, translatedLyrics),
            sourceStableKey = editableMetadataSourceStableKey(song),
            writesLyrics = writeLyrics,
            clearsMissingLyrics = writeLyrics,
            requiredEmbeddedPropertyKeys = requiredEmbeddedPropertyKeys
        )
    } ?: run {
        logEditableMetadataFailure(
            "taglib_read",
            sourceUri,
            IllegalStateException("TagLib metadata unavailable")
        )
        return EditableMetadataWriteTransaction(
            LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE
        )
    }

    if (metadataSnapshot.picturePlan == EditableCoverWritePlan.Unreadable) {
        NPLogger.w(
            TAG,
            "本地封面不可读，跳过嵌入封面并保留侧载恢复: " +
                "stage=cover_read, uri=$sourceUri"
        )
        return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
    }

    val rollbackUsed = AtomicBoolean(false)
    fun rollbackEmbeddedMetadata() {
        if (!rollbackUsed.compareAndSet(false, true)) return
        val restored = openWritableTagLibDescriptor(
            context = context,
            uri = sourceUri,
            file = resolved.file
        )?.use { target ->
            val propertiesRestored = runCatching {
                TagLib.savePropertyMap(
                    target.dup().detachFd(),
                    metadataSnapshot.existingProperties
                )
                true
            }.getOrElse { error ->
                logEditableMetadataFailure("rollback_properties", sourceUri, error)
                false
            }
            val picturesRestored = when (val picturePlan = metadataSnapshot.picturePlan) {
                is EditableCoverWritePlan.Update -> runCatching {
                    TagLib.savePictures(
                        target.dup().detachFd(),
                        picturePlan.originalPictures
                    )
                    true
                }.getOrElse { error ->
                    logEditableMetadataFailure("rollback_cover", sourceUri, error)
                    false
                }
                else -> true
            }
            propertiesRestored && picturesRestored
        } == true
        if (!restored) {
            NPLogger.e(TAG, "rollback local embedded metadata was not confirmed: $sourceUri")
        }
    }

    val propertyMapChanged = !propertyMapsEquivalent(
        metadataSnapshot.existingProperties,
        metadataSnapshot.updatedProperties
    )
    val restorePropertiesAfterCover = shouldRestoreEditablePropertiesAfterCoverWrite(
        audioExtension = resolved.fileExtension,
        writesCover = metadataSnapshot.picturePlan is EditableCoverWritePlan.Update
    )
    fun saveProperties(): Boolean {
        return openWritableTagLibDescriptor(
            context = context,
            uri = sourceUri,
            file = resolved.file
        )?.use { target ->
            runCatching {
                TagLib.savePropertyMap(target.dup().detachFd(), metadataSnapshot.updatedProperties)
            }.getOrElse { error ->
                logEditableMetadataFailure("property_write", sourceUri, error)
                false
            }
        } ?: false
    }
    if (!restorePropertiesAfterCover && propertyMapChanged && !saveProperties()) {
        rollbackEmbeddedMetadata()
        return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
    }

    val coverSaved = when (val picturePlan = metadataSnapshot.picturePlan) {
        EditableCoverWritePlan.Unchanged -> true
        EditableCoverWritePlan.Unreadable -> false
        is EditableCoverWritePlan.Update -> {
            openWritableTagLibDescriptor(
                context = context,
                uri = sourceUri,
                file = resolved.file
            )?.use { target ->
                runCatching {
                    TagLib.savePictures(target.dup().detachFd(), picturePlan.pictures)
                }.getOrElse { error ->
                    logEditableMetadataFailure("cover_write", sourceUri, error)
                    false
                }
            } ?: false
        }
    }
    if (!coverSaved) {
        rollbackEmbeddedMetadata()
        return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
    }
    if (restorePropertiesAfterCover && !saveProperties()) {
        rollbackEmbeddedMetadata()
        return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
    }

    val verified = verifyEditableMetadataReadback(
        context = context,
        song = song,
        sourceUri = sourceUri,
        resolved = resolved,
        metadataSnapshot = metadataSnapshot
    )
    if (!verified) {
        logEditableMetadataFailure(
            "readback",
            sourceUri,
            IllegalStateException("required metadata readback mismatch")
        )
        rollbackEmbeddedMetadata()
        return EditableMetadataWriteTransaction(LocalMediaMetadataWriteOutcome.FAILED)
    }

    if (metadataSnapshot.picturePlan !is EditableCoverWritePlan.Unchanged) {
        invalidateLocalCoverLookupCache(context, sourceUri, resolved)
    }
    return EditableMetadataWriteTransaction(
        outcome = LocalMediaMetadataWriteOutcome.SUCCESS,
        rollback = ::rollbackEmbeddedMetadata
    )
}

internal fun LocalMediaSupport.verifyEditableMetadataReadback(
    context: Context,
    song: SongItem,
    sourceUri: Uri,
    resolved: ResolvedInspectableLocalMedia,
    metadataSnapshot: EditableMetadataSnapshot
): Boolean {
    return retryEditableMetadataReadback(sourceUri.scheme) {
        openTagLibDescriptor(
            context = context,
            uri = sourceUri,
            file = resolved.file
        )?.use { target ->
            val propertyMap = loadTagLibPropertyMap(target) ?: return@use false
            val propertiesMatch = if (
                metadataSnapshot.requiredEmbeddedPropertyKeys.isNotEmpty()
            ) {
                hasExpectedPropertyMapValues(
                    actual = propertyMap,
                    expected = metadataSnapshot.updatedProperties,
                    requiredKeys = metadataSnapshot.requiredEmbeddedPropertyKeys
                )
            } else {
                hasExpectedEditableMetadata(
                    propertyMap = propertyMap,
                    title = song.displayName(),
                    artist = song.displayArtist(),
                    lyrics = if (metadataSnapshot.writesLyrics) {
                        song.matchedLyric ?: song.originalLyric
                    } else {
                        null
                    },
                    translatedLyrics = if (metadataSnapshot.writesLyrics) {
                        song.matchedTranslatedLyric ?: song.originalTranslatedLyric
                    } else {
                        null
                    },
                    romanizedLyrics = if (metadataSnapshot.writesLyrics) {
                        song.matchedRomanizedLyric ?: song.originalRomanizedLyric
                    } else {
                        null
                    },
                    audioExtension = resolved.fileExtension,
                    expectedStandardLyrics = metadataSnapshot.expectedStandardLyrics,
                    verifyStandardLyrics = metadataSnapshot.writesLyrics,
                    verifyMissingLyrics = metadataSnapshot.clearsMissingLyrics,
                    sourceStableKey = metadataSnapshot.sourceStableKey
                )
            }
            val coverMatch = when (val picturePlan = metadataSnapshot.picturePlan) {
                EditableCoverWritePlan.Unchanged -> true
                EditableCoverWritePlan.Unreadable -> false
                is EditableCoverWritePlan.Update -> {
                    val pictures = runCatching {
                        TagLib.getPictures(target.dup().detachFd())
                    }.getOrElse { error ->
                        logEditableMetadataFailure("readback_cover", sourceUri, error)
                        return@use false
                    }
                    hasExpectedEditableCover(
                        actualPictures = pictures,
                        expectedPictures = picturePlan.pictures,
                        audioExtension = resolved.fileExtension
                    )
                }
            }
            propertiesMatch && coverMatch
        } == true
    }
}

internal fun LocalMediaSupport.writeEditableMetadataThroughStagedContentCopy(
    context: Context,
    song: SongItem,
    sourceUri: Uri,
    coverReference: String?,
    writeCover: Boolean,
    writeLyrics: Boolean,
    fallbackOutcome: LocalMediaMetadataWriteOutcome,
    embeddedPropertyMapOverride: PropertyMap? = null,
    requiredEmbeddedPropertyKeys: Set<String> = emptySet()
): EditableMetadataWriteTransaction {
    val startedAtMs = SystemClock.elapsedRealtime()
    val stagingDirectory = LocalMediaMetadataRecoveryStore.stagingDirectory(context)
    if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
        NPLogger.w(TAG, "create staged metadata directory failed")
        return EditableMetadataWriteTransaction(fallbackOutcome)
    }
    val extension = resolveEditableMediaExtension(song, sourceUri)
    val backup = runCatching {
        File.createTempFile("metadata-source-", ".${extension}", stagingDirectory)
    }.getOrNull() ?: return EditableMetadataWriteTransaction(fallbackOutcome)
    val updated = runCatching {
        File.createTempFile("metadata-updated-", ".${extension}", stagingDirectory)
    }.getOrNull() ?: run {
        backup.delete()
        return EditableMetadataWriteTransaction(fallbackOutcome)
    }
    var recoveryRecord: LocalMetadataRecoveryRecord? = null
    var transactionReturned = false
    try {
        val sourceInfo = queryContentInfo(context, sourceUri)
        val sourceFile = directFilePath(sourceUri)?.let(::File)?.takeIf(File::isFile)
        val sourceExpectedBytes = sourceInfo.sizeBytes ?: sourceFile?.length()
        val sourceInput = sourceFile?.inputStream()
            ?: context.contentResolver.openInputStream(sourceUri)
        val copied = sourceInput?.use { input ->
            FileOutputStream(backup).use { output ->
                val copiedBytes = input.copyTo(output)
                output.fd.sync()
                if (copiedBytes <= 0L) {
                    throw IOException("staged source copy is empty")
                }
                if (sourceExpectedBytes != null &&
                    sourceExpectedBytes > 0L &&
                    copiedBytes < sourceExpectedBytes
                ) {
                    throw IOException(
                        "staged source copy is truncated: $copiedBytes/$sourceExpectedBytes"
                    )
                }
            }
            backup.length() > 0L
        } ?: false
        if (!copied) {
            NPLogger.w(
                TAG,
                "暂存元数据写入源音频为空或不可读: " +
                    "stage=staged_copy, uri=$sourceUri"
            )
            return EditableMetadataWriteTransaction(fallbackOutcome)
        }
        FileInputStream(backup).use { input ->
            FileOutputStream(updated).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        val stagedSong = song.copy(
            mediaUri = Uri.fromFile(updated).toString(),
            localFilePath = updated.absolutePath,
            localFileName = updated.name
        )
        val stagedOutcome = writeEditableMetadataDirect(
            context = context,
            song = stagedSong,
            sourceUri = Uri.fromFile(updated),
            coverReference = coverReference,
            writeCover = writeCover,
            writeLyrics = writeLyrics,
            embeddedPropertyMapOverride = embeddedPropertyMapOverride,
            requiredEmbeddedPropertyKeys = requiredEmbeddedPropertyKeys
        )
        if (stagedOutcome != LocalMediaMetadataWriteOutcome.SUCCESS) {
            NPLogger.w(
                TAG,
                "暂存 TagLib 回写未确认，保留原音频并等待重试: " +
                    "stage=staged_taglib, uri=$sourceUri, outcome=$stagedOutcome"
            )
            return EditableMetadataWriteTransaction(fallbackOutcome)
        }
        val preparedRecord = LocalMediaMetadataRecoveryStore.begin(
            context = context,
            targetReference = sourceUri.toString(),
            backupFile = backup,
            updatedFile = updated,
            originalLastModifiedMs = sourceFile?.lastModified()
                ?.takeIf { it > 0L }
                ?: sourceInfo.lastModifiedMs?.takeIf { it > 0L }
        )
        recoveryRecord = preparedRecord
        val replaceStartedAtMs = SystemClock.elapsedRealtime()
        var activeRecord = LocalMediaMetadataRecoveryStore.markReplacing(preparedRecord)
        recoveryRecord = activeRecord
        if (!LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
                context = context,
                targetReference = sourceUri.toString(),
                source = updated,
                lastModifiedMs = activeRecord.originalLastModifiedMs
            ) || !retryEditableMetadataReadback(sourceUri.scheme) {
                LocalMediaMetadataRecoveryStore.targetMatches(
                    context = context,
                    targetReference = sourceUri.toString(),
                    expectedSha256 = activeRecord.updatedSha256
                )
            }
        ) {
            if (!LocalMediaMetadataRecoveryStore.rollback(context, activeRecord)) {
                NPLogger.e(TAG, "元信息替换失败且原音频恢复未确认: $sourceUri")
            }
            return EditableMetadataWriteTransaction(fallbackOutcome)
        }
        activeRecord = LocalMediaMetadataRecoveryStore.markTargetVerified(activeRecord)
        recoveryRecord = activeRecord
        if (writeCover) {
            val resolvedSource = runCatching {
                resolveInspectableLocalMedia(
                    context = context,
                    uri = sourceUri,
                    allowDescriptorFallback = true
                )
            }.getOrNull()
            invalidateLocalCoverLookupCache(
                context = context,
                uri = sourceUri,
                resolved = resolvedSource
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val replaceElapsedMs = SystemClock.elapsedRealtime() - replaceStartedAtMs
        val message = "staged metadata write completed for $sourceUri: " +
            "copyAndTagLibMs=${replaceStartedAtMs - startedAtMs}, " +
            "replaceMs=$replaceElapsedMs, totalMs=$elapsedMs"
        if (elapsedMs >= EDITABLE_METADATA_WRITE_BUDGET_MS) {
            NPLogger.w(TAG, "$message, overBudget=true")
        } else {
            NPLogger.d(TAG, "$message, overBudget=false")
        }
        val verifiedRecord = activeRecord
        transactionReturned = true
        return EditableMetadataWriteTransaction(
            outcome = LocalMediaMetadataWriteOutcome.SUCCESS,
            rollback = {
                if (!LocalMediaMetadataRecoveryStore.rollback(context, verifiedRecord)) {
                    NPLogger.e(TAG, "回滚完整音频备份失败，恢复凭据已保留: $sourceUri")
                }
            },
            commit = { LocalMediaMetadataRecoveryStore.complete(verifiedRecord) }
        )
    } catch (error: CancellationException) {
        recoveryRecord?.let { record ->
            if (!LocalMediaMetadataRecoveryStore.rollback(context, record)) {
                NPLogger.e(TAG, "元信息取消后原音频恢复未确认: $sourceUri")
            }
        }
        throw error
    } catch (error: Exception) {
        logEditableMetadataFailure("staged_copy", sourceUri, error)
        recoveryRecord?.let { record ->
            if (!LocalMediaMetadataRecoveryStore.rollback(context, record)) {
                NPLogger.e(TAG, "元信息异常后原音频恢复未确认: $sourceUri")
            }
        }
        return EditableMetadataWriteTransaction(fallbackOutcome)
    } finally {
        val journalRetained = recoveryRecord?.journalFile?.exists() == true
        if (!transactionReturned && !journalRetained) {
            if (backup.exists() && !backup.delete()) {
                NPLogger.w(TAG, "delete staged metadata backup failed: ${backup.name}")
            }
            if (updated.exists() && !updated.delete()) {
                NPLogger.w(TAG, "delete staged metadata update failed: ${updated.name}")
            }
        }
    }
}

internal fun LocalMediaSupport.resolveNearbyCoverUriInternal(context: Context, song: SongItem): String? {
    // 即使 MediaStore 无法直接暴露同级 Covers 目录，元数据侧载仍保存权威的 SAF 封面引用
    runCatching {
        readLocalMetadataSidecarFast(context = context, song = song)
            ?.coverPath
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { isUsableCoverReference(context, it) }
    }.getOrNull()?.let { return it }

    val uri = song.localMediaUri()
    val directFile = song.localFilePath
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it.startsWith("content://", ignoreCase = true) }
        ?.let(::File)
        ?.takeIf(File::isFile)
    if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
        val contentUri: Uri = uri
        val resolved = runCatching {
            resolveInspectableLocalMedia(
                context = context,
                uri = contentUri,
                allowDescriptorFallback = false
            )
        }.getOrNull()
        resolved?.let {
            findNearbyCoverReference(
                context = context,
                uri = contentUri,
                file = it.file,
                displayName = it.displayName
            )
        }?.let { return it }
    }
    if (directFile != null) {
        return findNearbyCoverReference(
            context = context,
            uri = Uri.fromFile(directFile),
            file = directFile,
            displayName = directFile.name
        )
    }
    if (uri == null) return null
    val resolved = runCatching {
        resolveInspectableLocalMedia(
            context = context,
            uri = uri,
            allowDescriptorFallback = false
        )
    }.getOrNull() ?: return null
    return findNearbyCoverReference(
        context = context,
        uri = uri,
        file = resolved.file,
        displayName = resolved.displayName
    )
}
