package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.storage.StorageUsageItem
import moe.ouom.neriplayer.data.storage.StorageUsageSection
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassScene
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.util.format.formatFileSize

@Composable
internal fun StorageCacheDetailsContent(
    storageDetails: StorageUsageSummary,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    AnimatedContent(
        targetState = isScanning,
        transitionSpec = {
            (fadeIn(animationSpec = tween(240)) +
                scaleIn(initialScale = 0.98f, animationSpec = tween(240)))
                .togetherWith(
                    fadeOut(animationSpec = tween(120)) +
                        scaleOut(targetScale = 1.02f, animationSpec = tween(120))
                )
        },
        label = "storage_scan_content"
    ) { scanning ->
        AdvancedGlassScene(active = scanning == isScanning) {
            if (scanning) {
                StorageScanCard()
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val useTwoColumns = maxWidth >= TABLET_CONTENT_MIN_WIDTH
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StorageUsageSummaryCard(
                            storageDetails = storageDetails,
                            onRefresh = onRefresh,
                            onClearCache = onClearCache,
                            onOpenSystemSettings = onOpenSystemSettings
                        )
                        if (storageDetails.sections.isEmpty()) {
                            MiuixSettingsSectionCard {
                                Text(
                                    text = stringResource(R.string.storage_details_empty),
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (useTwoColumns) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                StorageUsageSectionColumn(
                                    sections = storageDetails.sections.filterIndexed { index, _ ->
                                        index % 2 == 0
                                    },
                                    totalSizeBytes = storageDetails.totalSizeBytes,
                                    modifier = Modifier.weight(1f)
                                )
                                StorageUsageSectionColumn(
                                    sections = storageDetails.sections.filterIndexed { index, _ ->
                                        index % 2 == 1
                                    },
                                    totalSizeBytes = storageDetails.totalSizeBytes,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            StorageUsageSectionColumn(
                                sections = storageDetails.sections,
                                totalSizeBytes = storageDetails.totalSizeBytes
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageUsageSectionColumn(
    sections: List<StorageUsageSection>,
    totalSizeBytes: Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        sections.forEach { section ->
            StorageUsageSectionCard(
                section = section,
                totalSizeBytes = totalSizeBytes
            )
        }
    }
}

@Composable
private fun StorageScanCard() {
    val scanStageLabels = listOf(
        stringResource(R.string.storage_scan_stage_cache),
        stringResource(R.string.storage_scan_stage_database),
        stringResource(R.string.storage_scan_stage_finalize)
    )
    var activeStage by remember { mutableIntStateOf(0) }

    LaunchedEffect(scanStageLabels) {
        while (isActive) {
            delay(SCAN_STAGE_DURATION_MS)
            activeStage = (activeStage + 1) % scanStageLabels.size
        }
    }

    MiuixSettingsSectionCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StorageScanGlyph()
            Text(
                text = stringResource(R.string.storage_scan_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.storage_scan_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            scanStageLabels.forEachIndexed { index, label ->
                StorageScanStage(
                    label = label,
                    active = index == activeStage
                )
            }
        }
    }
}

@Composable
private fun StorageScanGlyph() {
    val transition = rememberInfiniteTransition(label = "storage_scan")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(SCAN_ROTATION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "storage_scan_rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SCAN_PULSE_DURATION_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "storage_scan_pulse"
    )
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primaryContainer
    val strokeWidth = with(LocalDensity.current) { 5.dp.toPx() }

    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = pulse
                    scaleY = pulse
                }
        ) {
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = primary,
                startAngle = -78f,
                sweepAngle = 224f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Icon(
            imageVector = Icons.Outlined.SdStorage,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = primary
        )
    }
}

@Composable
private fun StorageScanStage(
    label: String,
    active: Boolean
) {
    val dotColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = if (active) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}

@Composable
private fun StorageUsageSummaryCard(
    storageDetails: StorageUsageSummary,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    MiuixSettingsSectionCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StorageUsageRing(
                    fraction = storageDetails.cleanableSizeBytes
                        .toUsageFraction(storageDetails.totalSizeBytes)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.storage_details_total),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(storageDetails.totalSizeBytes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.storage_details_file_count,
                            storageDetails.totalFileCount,
                            storageDetails.totalFileCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.storage_cleanable_total),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(storageDetails.cleanableSizeBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiuixSettingsOutlinedButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.storage_scan_refresh),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    MiuixSettingsOutlinedButton(onClick = onClearCache) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = stringResource(R.string.action_clear),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            MiuixSettingsTextButton(onClick = onOpenSystemSettings) {
                Text(stringResource(R.string.storage_open_system_settings))
            }
        }
    }
}

@Composable
private fun StorageUsageRing(fraction: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primaryContainer
    val strokeWidth = with(LocalDensity.current) { 5.dp.toPx() }

    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Icon(
            imageVector = Icons.Outlined.Storage,
            contentDescription = null,
            tint = primary
        )
    }
}

@Composable
private fun StorageUsageSectionCard(
    section: StorageUsageSection,
    totalSizeBytes: Long
) {
    val sectionFraction = section.sizeBytes.toUsageFraction(totalSizeBytes)

    MiuixSettingsSectionCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatFileSize(section.sizeBytes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { sectionFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
            )
            section.items
                .sortedByDescending(StorageUsageItem::sizeBytes)
                .forEach { item -> StorageUsageItemRow(item) }
        }
    }
}

@Composable
private fun StorageUsageItemRow(item: StorageUsageItem) {
    val icon = item.kind.toStorageIcon()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = item.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatFileSize(item.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.countDescription ?: item.databaseRecordCount?.let { recordCount ->
                    stringResource(R.string.storage_details_cache_record_count, recordCount)
                } ?: pluralStringResource(
                    R.plurals.storage_details_file_count,
                    item.fileCount,
                    item.fileCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun StorageUsageItemKind.toStorageIcon(): ImageVector {
    return when (this) {
        StorageUsageItemKind.AudioCache -> Icons.Outlined.MusicNote
        StorageUsageItemKind.ImageCache -> Icons.Outlined.PictureInPictureAlt
        StorageUsageItemKind.DownloadStaging -> Icons.Outlined.CloudDownload
        StorageUsageItemKind.SharedMedia -> Icons.Outlined.Share
        StorageUsageItemKind.NeteasePlaylistCache -> Icons.Outlined.LibraryMusic
        StorageUsageItemKind.BiliFavoriteCache -> Icons.Outlined.Favorite
        StorageUsageItemKind.BiliArchiveCache -> Icons.Outlined.VideoLibrary
        StorageUsageItemKind.YouTubePlaylistCache -> Icons.Outlined.PlayArrow
        StorageUsageItemKind.OtherCache -> Icons.Outlined.Layers
        StorageUsageItemKind.DownloadedMusic -> Icons.Outlined.DownloadDone
        StorageUsageItemKind.DownloadedLyrics -> Icons.Outlined.Subtitles
        StorageUsageItemKind.DownloadedCovers -> Icons.Outlined.PictureInPictureAlt
        StorageUsageItemKind.DownloadIndex -> Icons.Outlined.Tab
        StorageUsageItemKind.LogFiles -> Icons.Outlined.Description
        StorageUsageItemKind.CrashLogs -> Icons.Outlined.BugReport
        StorageUsageItemKind.LocalCovers -> Icons.Outlined.ColorLens
        StorageUsageItemKind.CustomBackground -> Icons.Outlined.Wallpaper
        StorageUsageItemKind.LegacyMigrationFiles -> Icons.Outlined.Backup
        StorageUsageItemKind.Database -> Icons.Outlined.Memory
        StorageUsageItemKind.AppData -> Icons.Outlined.Settings
    }
}

private fun Long.toUsageFraction(totalSizeBytes: Long): Float {
    if (totalSizeBytes <= 0L) return 0f
    return (toDouble() / totalSizeBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

private const val SCAN_ROTATION_DURATION_MS = 1_550
private const val SCAN_PULSE_DURATION_MS = 850
private const val SCAN_STAGE_DURATION_MS = 900L
private val TABLET_CONTENT_MIN_WIDTH = 620.dp
