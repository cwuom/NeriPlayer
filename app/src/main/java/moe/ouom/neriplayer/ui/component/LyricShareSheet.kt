package moe.ouom.neriplayer.ui.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import coil.Coil
import coil.compose.AsyncImage
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import java.io.File
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private const val MaxLyricShareCharacters = 150
private const val LyricShareCardSizePx = 1080
private const val LyricShareCardCacheDir = "lyric_share_cards"
private const val LyricShareCardBrand = "NeriPlayer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricShareSheet(
    song: SongItem,
    lyrics: List<LyricEntry>,
    initialLine: LyricEntry,
    queue: List<SongItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareableLyrics = remember(lyrics) {
        lyrics.filter { it.text.isNotBlank() }
    }
    val initialKey = remember(shareableLyrics, initialLine) {
        resolveInitialShareLineKey(shareableLyrics, initialLine)
    }
    val initialIndex = remember(shareableLyrics, initialKey) {
        shareableLyrics.indices
            .firstOrNull { index -> shareLineKey(index, shareableLyrics[index]) == initialKey }
            ?: 0
    }
    val initialScrollIndex = remember(initialIndex) {
        (initialIndex - 2).coerceAtLeast(0)
    }
    val lyricsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollIndex
    )
    var selectedKeys by remember(shareableLyrics, initialKey) {
        mutableStateOf(setOfNotNull(initialKey))
    }
    val selectedLines = remember(shareableLyrics, selectedKeys) {
        shareableLyrics.filterIndexed { index, line ->
            shareLineKey(index, line) in selectedKeys
        }
    }
    val selectedText = remember(selectedLines) {
        selectedLines.joinToString(separator = "\n") { it.text }
    }
    val selectedCharCount = selectedText.length
    val coverUrl = remember(song, context) { song.displayCoverUrl(context) }
    var isSharingCard by remember { mutableStateOf(false) }

    LaunchedEffect(shareableLyrics, initialKey) {
        if (shareableLyrics.isEmpty()) {
            onDismiss()
        }
    }
    LaunchedEffect(shareableLyrics, initialScrollIndex) {
        if (shareableLyrics.isNotEmpty()) {
            lyricsListState.scrollToItem(initialScrollIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            LyricShareHeader(
                coverUrl = coverUrl,
                title = stringResource(R.string.lyric_share_selected_lines, selectedLines.size),
                subtitle = stringResource(
                    R.string.lyric_share_character_count,
                    selectedCharCount,
                    MaxLyricShareCharacters
                )
            )

            Spacer(Modifier.height(18.dp))

            LazyColumn(
                state = lyricsListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(
                    items = shareableLyrics,
                    key = { index, line -> shareLineKey(index, line) }
                ) { index, line ->
                    val key = shareLineKey(index, line)
                    val selected = key in selectedKeys
                    LyricShareLine(
                        line = line,
                        selected = selected,
                        onToggle = {
                            val nextKeys = toggleShareLine(
                                currentKeys = selectedKeys,
                                toggledKey = key,
                                line = line,
                                shareableLyrics = shareableLyrics
                            )
                            if (nextKeys == selectedKeys && !selected) {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.lyric_share_character_limit,
                                        MaxLyricShareCharacters
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                selectedKeys = nextKeys
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            LyricShareActions(
                enabled = selectedText.isNotBlank(),
                cardInProgress = isSharingCard,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("lyrics", selectedText))
                        )
                        onDismiss()
                    }
                },
                onShareSong = {
                    scope.launch {
                        shareSong(context, song, queue)
                        onDismiss()
                    }
                },
                onShareCard = {
                    if (!isSharingCard) {
                        isSharingCard = true
                        scope.launch {
                            val result = runCatching {
                                createAndShareLyricCard(
                                    context = context,
                                    song = song,
                                    coverUrl = coverUrl,
                                    lyrics = selectedLines,
                                    queue = queue
                                )
                            }
                            result.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.lyric_share_card_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onSuccess {
                                onDismiss()
                            }
                            isSharingCard = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun LyricShareHeader(
    coverUrl: String?,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = LocalContext.current,
                        data = coverUrl,
                        sizePx = 192,
                        allowHardware = false
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(58.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricShareLine(
    line: LyricEntry,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val selectedBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.48f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
        )
    )
    val background = if (selected) {
        Modifier.background(selectedBrush, shape)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f), shape)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(background)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onToggle
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LyricShareActions(
    enabled: Boolean,
    cardInProgress: Boolean,
    onCopy: () -> Unit,
    onShareSong: () -> Unit,
    onShareCard: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onCopy,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.lyric_share_copy_lyrics)
            )
        }
        FilledTonalButton(
            onClick = onShareSong,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Outlined.Share,
                contentDescription = stringResource(R.string.lyric_share_song)
            )
        }
        FilledTonalButton(
            onClick = onShareCard,
            enabled = enabled && !cardInProgress,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Outlined.Wallpaper,
                contentDescription = stringResource(R.string.lyric_share_card)
            )
        }
    }
}

private fun toggleShareLine(
    currentKeys: Set<String>,
    toggledKey: String,
    line: LyricEntry,
    shareableLyrics: List<LyricEntry>
): Set<String> {
    if (toggledKey in currentKeys) {
        return if (currentKeys.size > 1) currentKeys - toggledKey else currentKeys
    }

    val currentText = shareableLyrics
        .filterIndexed { index, item -> shareLineKey(index, item) in currentKeys }
        .joinToString(separator = "\n") { it.text }
    val extraSeparator = if (currentText.isBlank()) 0 else 1
    val nextCharCount = currentText.length + extraSeparator + line.text.length
    return if (nextCharCount <= MaxLyricShareCharacters) {
        currentKeys + toggledKey
    } else {
        currentKeys
    }
}

private fun resolveInitialShareLineKey(
    lyrics: List<LyricEntry>,
    initialLine: LyricEntry
): String? {
    if (lyrics.isEmpty()) return null
    val exactIndex = lyrics.indexOfFirst { line ->
        line.startTimeMs == initialLine.startTimeMs &&
            line.text == initialLine.text
    }
    if (exactIndex >= 0) {
        return shareLineKey(exactIndex, lyrics[exactIndex])
    }

    val nearest = lyrics
        .mapIndexed { index, line -> index to kotlin.math.abs(line.startTimeMs - initialLine.startTimeMs) }
        .minByOrNull { it.second }
        ?.first
        ?: return shareLineKey(0, lyrics.first())
    return shareLineKey(nearest, lyrics[nearest])
}

private fun shareLineKey(index: Int, line: LyricEntry): String {
    return "$index:${line.startTimeMs}:${line.endTimeMs}:${line.text}"
}

private suspend fun shareSong(
    context: Context,
    song: SongItem,
    queue: List<SongItem>
) {
    if (song.isLocalSong()) {
        val shared = runCatching {
            LocalMediaSupport.shareSongFile(context, song)
        }.getOrElse { false }
        if (!shared) {
            Toast.makeText(
                context,
                context.getString(R.string.local_song_share_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
        return
    }

    val shareText = context.getString(
        R.string.nowplaying_share_song,
        song.displayName(),
        song.displayArtist(),
        buildRemoteSongShareUrl(song, queue)
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

private fun buildRemoteSongShareUrl(song: SongItem, queue: List<SongItem>): String {
    extractYouTubeMusicVideoId(song.mediaUri)?.let { videoId ->
        return "https://music.youtube.com/watch?v=$videoId"
    }

    if (song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)) {
        val videoParts = queue.filter {
            it.id == song.id && it.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
        }
        if (videoParts.size > 1) {
            val pageIndex = videoParts.indexOfFirst { it.album == song.album }
            if (pageIndex != -1) {
                return "https://www.bilibili.com/video/av${song.id}/?p=${pageIndex + 1}"
            }
        }
        return "https://www.bilibili.com/video/av${song.id}"
    }

    val mediaUri = song.mediaUri
    return when {
        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG) ->
            "https://music.163.com/#/song?id=${song.id}"
        !mediaUri.isNullOrBlank() &&
            (mediaUri.startsWith("https://") || mediaUri.startsWith("http://")) -> mediaUri
        else -> "https://music.163.com/#/song?id=${song.id}"
    }
}

private suspend fun createAndShareLyricCard(
    context: Context,
    song: SongItem,
    coverUrl: String?,
    lyrics: List<LyricEntry>,
    queue: List<SongItem>
) {
    val appContext = context.applicationContext
    val bitmap = withContext(Dispatchers.IO) {
        val coverBitmap = loadCoverBitmap(appContext, coverUrl)
        val appIconBitmap = loadAppIconBitmap(appContext)
        drawLyricShareCard(
            song = song,
            lyricsText = lyrics.joinToString(separator = "\n") { it.text },
            coverBitmap = coverBitmap,
            appIconBitmap = appIconBitmap
        )
    }
    val file = withContext(Dispatchers.IO) {
        try {
            saveLyricShareCard(appContext, bitmap, song)
        } finally {
            bitmap.recycle()
        }
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val shareText = buildLyricCardShareText(song, queue)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        putExtra(Intent.EXTRA_TITLE, song.displayName())
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

private suspend fun loadCoverBitmap(context: Context, coverUrl: String?): Bitmap? {
    if (coverUrl.isNullOrBlank()) return null
    return runCatching {
        val request = offlineCachedImageRequest(
            context = context,
            data = coverUrl,
            sizePx = 512,
            allowHardware = false
        )
        val result = Coil.imageLoader(context).execute(request)
        (result as? SuccessResult)
            ?.drawable
            ?.toBitmap(width = 512, height = 512, config = Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

private fun loadAppIconBitmap(context: Context): Bitmap? {
    return runCatching {
        context.packageManager
            .getApplicationIcon(context.applicationInfo)
            .toBitmap(width = 128, height = 128, config = Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

private fun drawLyricShareCard(
    song: SongItem,
    lyricsText: String,
    coverBitmap: Bitmap?,
    appIconBitmap: Bitmap?
): Bitmap {
    val size = LyricShareCardSizePx
    val width = size.toFloat()
    val height = size.toFloat()
    val padding = 88f
    val bottomBarHeight = 214f
    val bottomBarTop = height - bottomBarHeight
    val lyricsTop = 246f
    val lyricsMaxHeight = (bottomBarTop - lyricsTop - 48f).roundToInt()
    val lyricsLayout = buildLyricCardLayout(
        text = lyricsText,
        width = (width - padding * 2).roundToInt(),
        maxHeight = lyricsMaxHeight
    )

    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val cardRect = RectF(0f, 0f, width, height)
    val cardPath = Path().apply {
        addRoundRect(cardRect, 76f, 76f, Path.Direction.CW)
    }

    canvas.withClip(cardPath) {
        drawLyricCardBackground(this, cardRect, coverBitmap)
        drawLyricCardBrand(this, padding, appIconBitmap)
        withTranslation(padding, lyricsTop) {
            lyricsLayout.draw(this)
        }
        drawBlurredBottomBackdrop(
            canvas = this,
            sourceBitmap = bitmap,
            top = bottomBarTop,
            height = bottomBarHeight
        )
        drawSongInfoBar(
            canvas = this,
            song = song,
            coverBitmap = coverBitmap,
            top = bottomBarTop,
            height = bottomBarHeight,
            padding = padding
        )
    }
    return bitmap
}

private fun buildLyricCardLayout(
    text: String,
    width: Int,
    maxHeight: Int
): StaticLayout {
    var textSize = 66f
    while (textSize >= 44f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val layout = buildStaticLayout(
            text = text,
            paint = paint,
            width = width,
            maxLines = 5,
            lineSpacingExtra = 16f
        )
        if (layout.height <= maxHeight) return layout
        textSize -= 4f
    }

    val fallbackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        this.textSize = 44f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    return buildStaticLayout(
        text = text,
        paint = fallbackPaint,
        width = width,
        maxLines = 5,
        lineSpacingExtra = 12f
    )
}

private fun buildStaticLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    maxLines: Int,
    lineSpacingExtra: Float
): StaticLayout {
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setMaxLines(maxLines)
        .setLineSpacing(lineSpacingExtra, 1f)
        .setIncludePad(false)
        .build()
}

private fun drawLyricCardBackground(
    canvas: Canvas,
    rect: RectF,
    coverBitmap: Bitmap?
) {
    val seedColor = coverBitmap?.averageColor() ?: AndroidColor.rgb(156, 148, 80)
    if (coverBitmap != null) {
        val blurredCover = buildBlurredCardBackground(coverBitmap, rect.width().roundToInt())
        canvas.drawBitmap(blurredCover, null, rect, Paint(Paint.ANTI_ALIAS_FLAG))
    } else {
        val startColor = blendColor(seedColor, AndroidColor.WHITE, 0.18f)
        val endColor = blendColor(seedColor, AndroidColor.BLACK, 0.28f)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(startColor, seedColor, endColor),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, backgroundPaint)
    }

    canvas.drawRect(
        rect,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(
                    AndroidColor.argb(86, 255, 255, 255),
                    AndroidColor.argb(64, 0, 0, 0),
                    AndroidColor.argb(118, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    )
}

private fun drawLyricCardBrand(
    canvas: Canvas,
    padding: Float,
    appIconBitmap: Bitmap?
) {
    val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(142, 255, 255, 255)
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val iconSize = 48f
    val iconTop = 84f
    if (appIconBitmap != null) {
        drawRoundedBitmap(
            canvas = canvas,
            bitmap = appIconBitmap,
            rect = RectF(padding, iconTop, padding + iconSize, iconTop + iconSize),
            cornerRadius = 12f,
            alpha = 150
        )
    } else {
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(142, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(padding + 12f, 108f, 16f, iconPaint)
        canvas.drawCircle(padding + 38f, 92f, 9f, iconPaint)
        canvas.drawCircle(padding + 38f, 124f, 9f, iconPaint)
    }
    canvas.drawText(LyricShareCardBrand, padding + iconSize + 18f, 124f, brandPaint)
}

private fun drawBlurredBottomBackdrop(
    canvas: Canvas,
    sourceBitmap: Bitmap,
    top: Float,
    height: Float
) {
    val sourceTop = top.roundToInt().coerceIn(0, sourceBitmap.height - 1)
    val sourceHeight = height.roundToInt().coerceIn(1, sourceBitmap.height - sourceTop)
    val source = Bitmap.createBitmap(
        sourceBitmap,
        0,
        sourceTop,
        sourceBitmap.width,
        sourceHeight
    )
    val smallWidth = (source.width / 8).coerceAtLeast(1)
    val smallHeight = (source.height / 8).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
    val blurred = small.boxBlur(radius = 6, iterations = 2)
    val glass = Bitmap.createScaledBitmap(blurred, source.width, source.height, true)
    val target = RectF(0f, top, sourceBitmap.width.toFloat(), top + height)
    canvas.drawBitmap(glass, null, target, Paint(Paint.ANTI_ALIAS_FLAG))
}

private fun buildBlurredCardBackground(
    coverBitmap: Bitmap,
    targetSize: Int
): Bitmap {
    val base = createBitmap(targetSize, targetSize)
    Canvas(base).drawBitmap(
        coverBitmap,
        null,
        RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()),
        Paint(Paint.ANTI_ALIAS_FLAG)
    )
    val smallSize = (targetSize / 8).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(base, smallSize, smallSize, true)
    val blurred = small.boxBlur(radius = 7, iterations = 3)
    return Bitmap.createScaledBitmap(blurred, targetSize, targetSize, true)
}

private fun drawSongInfoBar(
    canvas: Canvas,
    song: SongItem,
    coverBitmap: Bitmap?,
    top: Float,
    height: Float,
    padding: Float
) {
    canvas.drawRect(
        0f,
        top,
        LyricShareCardSizePx.toFloat(),
        top + height,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(70, 255, 255, 255)
        }
    )

    val coverSize = 132f
    val coverTop = top + (height - coverSize) / 2f
    drawCover(canvas, coverBitmap, padding, coverTop, coverSize, song.displayName())

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 46f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(224, 255, 255, 255)
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val textLeft = padding + coverSize + 28f
    val textWidth = LyricShareCardSizePx - textLeft - padding
    drawSingleLine(canvas, song.displayName(), textLeft, top + 82f, textWidth, titlePaint)
    drawSingleLine(canvas, song.displayArtist(), textLeft, top + 136f, textWidth, artistPaint)
}

private fun drawRoundedBitmap(
    canvas: Canvas,
    bitmap: Bitmap,
    rect: RectF,
    cornerRadius: Float,
    alpha: Int = 255
) {
    val path = Path().apply {
        addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.alpha = alpha.coerceIn(0, 255)
    }
    canvas.withClip(path) {
        drawBitmap(bitmap, null, rect, paint)
    }
}

private fun drawCover(
    canvas: Canvas,
    coverBitmap: Bitmap?,
    left: Float,
    top: Float,
    size: Float,
    fallbackText: String
) {
    val rect = RectF(left, top, left + size, top + size)
    if (coverBitmap != null) {
        val path = Path().apply {
            addRoundRect(rect, 28f, 28f, Path.Direction.CW)
        }
        canvas.withClip(path) {
            drawBitmap(coverBitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        return
    }

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            left,
            top,
            left + size,
            top + size,
            AndroidColor.rgb(229, 88, 112),
            AndroidColor.rgb(90, 138, 168),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRoundRect(rect, 28f, 28f, paint)
    val letter = fallbackText.trim().firstOrNull()?.toString().orEmpty()
    if (letter.isNotBlank()) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, rect.centerX(), baseline, textPaint)
    }
}

private fun Bitmap.averageColor(): Int {
    val stepX = (width / 48).coerceAtLeast(1)
    val stepY = (height / 48).coerceAtLeast(1)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val color = getPixel(x, y)
            val alpha = AndroidColor.alpha(color)
            if (alpha > 24) {
                red += AndroidColor.red(color)
                green += AndroidColor.green(color)
                blue += AndroidColor.blue(color)
                count += 1
            }
            x += stepX
        }
        y += stepY
    }
    if (count == 0L) return AndroidColor.rgb(156, 148, 80)
    return AndroidColor.rgb(
        (red / count).toInt(),
        (green / count).toInt(),
        (blue / count).toInt()
    )
}

private fun blendColor(
    from: Int,
    to: Int,
    ratio: Float
): Int {
    val safeRatio = ratio.coerceIn(0f, 1f)
    val inverse = 1f - safeRatio
    return AndroidColor.rgb(
        (AndroidColor.red(from) * inverse + AndroidColor.red(to) * safeRatio).roundToInt(),
        (AndroidColor.green(from) * inverse + AndroidColor.green(to) * safeRatio).roundToInt(),
        (AndroidColor.blue(from) * inverse + AndroidColor.blue(to) * safeRatio).roundToInt()
    )
}

private fun Bitmap.boxBlur(
    radius: Int,
    iterations: Int
): Bitmap {
    val working = copy(Bitmap.Config.ARGB_8888, true)
    repeat(iterations.coerceAtLeast(1)) {
        working.boxBlurOnce(radius.coerceAtLeast(1))
    }
    return working
}

private fun Bitmap.boxBlurOnce(radius: Int) {
    val bitmapWidth = width
    val bitmapHeight = height
    val pixels = IntArray(bitmapWidth * bitmapHeight)
    val temp = IntArray(bitmapWidth * bitmapHeight)
    getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

    for (y in 0 until bitmapHeight) {
        for (x in 0 until bitmapWidth) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (offset in -radius..radius) {
                val sampleX = (x + offset).coerceIn(0, bitmapWidth - 1)
                val color = pixels[y * bitmapWidth + sampleX]
                alpha += AndroidColor.alpha(color)
                red += AndroidColor.red(color)
                green += AndroidColor.green(color)
                blue += AndroidColor.blue(color)
                count += 1
            }
            temp[y * bitmapWidth + x] = AndroidColor.argb(
                alpha / count,
                red / count,
                green / count,
                blue / count
            )
        }
    }

    for (y in 0 until bitmapHeight) {
        for (x in 0 until bitmapWidth) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (offset in -radius..radius) {
                val sampleY = (y + offset).coerceIn(0, bitmapHeight - 1)
                val color = temp[sampleY * bitmapWidth + x]
                alpha += AndroidColor.alpha(color)
                red += AndroidColor.red(color)
                green += AndroidColor.green(color)
                blue += AndroidColor.blue(color)
                count += 1
            }
            pixels[y * bitmapWidth + x] = AndroidColor.argb(
                alpha / count,
                red / count,
                green / count,
                blue / count
            )
        }
    }

    setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
}

private fun buildLyricCardShareText(
    song: SongItem,
    queue: List<SongItem>
): String {
    val titleLine = "${song.displayName()} - ${song.displayArtist()}"
    if (song.isLocalSong()) return titleLine
    return "$titleLine\n${buildRemoteSongShareUrl(song, queue)}"
}

private fun drawSingleLine(
    canvas: Canvas,
    text: String,
    x: Float,
    baseline: Float,
    maxWidth: Float,
    paint: TextPaint
) {
    val ellipsized = TextUtils.ellipsize(
        text,
        paint,
        maxWidth,
        TextUtils.TruncateAt.END
    ).toString()
    canvas.drawText(ellipsized, x, baseline, paint)
}

private fun saveLyricShareCard(
    context: Context,
    bitmap: Bitmap,
    song: SongItem
): File {
    val dir = File(context.cacheDir, LyricShareCardCacheDir).apply {
        if (!exists()) mkdirs()
    }
    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
    val safeId = "${song.id}-${System.currentTimeMillis()}"
    val file = File(dir, "neriplayer-lyrics-$safeId.png")
    file.outputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
    return file
}
