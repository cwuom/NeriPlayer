package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.lyrics.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.data.settings.lyrics.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.lyrics.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.lyrics.MIN_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.lyrics.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.lyrics.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.lyrics.scaledLyricFontSize
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import kotlin.math.roundToInt

private val LyricOffsetStepMsFloat = LYRIC_DEFAULT_OFFSET_STEP_MS.toFloat()

internal data class LyricOffsetSliderRange(val min: Long, val max: Long, val steps: Int)

internal fun resolveLyricOffsetSliderRange(currentOffset: Long): LyricOffsetSliderRange {
    val min = minOf(MIN_LYRIC_DEFAULT_OFFSET_MS, currentOffset)
    val max = maxOf(MAX_LYRIC_DEFAULT_OFFSET_MS, currentOffset)
    val steps = (((max - min) / LYRIC_DEFAULT_OFFSET_STEP_MS).toInt() - 1).coerceAtLeast(0)
    return LyricOffsetSliderRange(min, max, steps)
}

internal fun snapLyricOffset(value: Float): Long =
    (value / LyricOffsetStepMsFloat).roundToInt() * LYRIC_DEFAULT_OFFSET_STEP_MS

internal fun formatLyricOffset(value: Long): String =
    "${if (value > 0) "+" else ""}$value ms"

internal fun lyricOffsetTextColor(
    offset: Long,
    positive: Color,
    negative: Color,
    neutral: Color
): Color = when {
    offset > 0 -> positive
    offset < 0 -> negative
    else -> neutral
}

internal fun lyricSecondaryToggleTitle(hasTranslation: Boolean, hasPhonetic: Boolean): Int =
    if (!hasTranslation && hasPhonetic) CoreCommonR.string.lyrics_secondary_mode_phonetic
    else CoreCommonR.string.settings_show_lyric_translation

internal fun lyricSecondaryToggleDescription(hasTranslation: Boolean, hasPhonetic: Boolean): Int =
    if (!hasTranslation && hasPhonetic) CoreCommonR.string.lyrics_phonetic_only_desc
    else CoreCommonR.string.settings_show_lyric_translation_desc

internal data class LyricTranslationToggleCopy(val title: Int, val description: Int)

internal fun lyricTranslationToggleCopy(
    hasTranslation: Boolean,
    hasPhonetic: Boolean
): LyricTranslationToggleCopy = LyricTranslationToggleCopy(
    title = lyricSecondaryToggleTitle(hasTranslation, hasPhonetic),
    description = lyricSecondaryToggleDescription(hasTranslation, hasPhonetic)
)

internal fun lyricPhoneticHint(translationEnabled: Boolean, hasPhonetic: Boolean): Int = when {
    !translationEnabled -> CoreCommonR.string.lyrics_translation_use_phonetic_requires_translation
    !hasPhonetic -> CoreCommonR.string.lyrics_translation_use_phonetic_unavailable
    else -> CoreCommonR.string.lyrics_translation_use_phonetic_desc
}

internal fun isLyricPhoneticSwitchEnabled(translationEnabled: Boolean, hasPhonetic: Boolean): Boolean =
    translationEnabled && hasPhonetic

internal fun isLyricPhoneticSwitchChecked(
    translationEnabled: Boolean,
    hasPhonetic: Boolean,
    phoneticSelected: Boolean
): Boolean = isLyricPhoneticSwitchEnabled(translationEnabled, hasPhonetic) && phoneticSelected

internal fun lyricPhoneticSwitchAction(
    hasPhonetic: Boolean,
    onCheckedChange: (Boolean) -> Unit
): (Boolean) -> Unit = if (hasPhonetic) onCheckedChange else { _ -> }

internal fun booleanToggleClick(checked: Boolean, onCheckedChange: (Boolean) -> Unit): () -> Unit =
    { onCheckedChange(!checked) }

private fun Modifier.lyricToggleModifier(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(12.dp))
    .clickable(enabled = enabled, onClick = booleanToggleClick(checked, onCheckedChange))

internal class LyricBehaviorSheetState(initialOffset: Long) {
    var currentOffset by mutableLongStateOf(initialOffset)
        private set

    val sliderRange: LyricOffsetSliderRange get() = resolveLyricOffsetSliderRange(currentOffset)

    fun setSliderValue(value: Float) {
        currentOffset = snapLyricOffset(value)
    }
}

internal class LyricBehaviorActionOwner(
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val song: SongItem,
    private val offsetState: LyricBehaviorSheetState
) {
    fun setTranslation(enabled: Boolean) {
        scope.launch { settingsRepo.setShowLyricTranslation(enabled) }
    }

    fun setPhonetic(enabled: Boolean) {
        scope.launch { settingsRepo.setLyricTranslationUsePhonetic(enabled) }
    }

    fun commitOffset() {
        scope.launch { PlayerManager.updateUserLyricOffset(song, offsetState.currentOffset) }
    }
}

internal class LyricFontSizeSheetState(initialLyric: Float, initialTranslation: Float) {
    private var lastExternalLyric = initialLyric
    private var lastExternalTranslation = initialTranslation
    var lyricValue by mutableFloatStateOf(normalizeLyricFontScale(initialLyric))
        private set
    var translationValue by mutableFloatStateOf(normalizeLyricFontScale(initialTranslation))
        private set

    fun updateLyricFromSlider(value: Float) {
        lyricValue = value
    }

    fun updateTranslationFromSlider(value: Float) {
        translationValue = value
    }

    fun syncExternalInputs(lyric: Float, translation: Float) {
        if (lastExternalLyric != lyric) {
            lastExternalLyric = lyric
            lyricValue = normalizeLyricFontScale(lyric)
        }
        if (lastExternalTranslation != translation) {
            lastExternalTranslation = translation
            translationValue = normalizeLyricFontScale(translation)
        }
    }
}

@Composable
fun LyricOffsetSheet(song: SongItem, onDismiss: () -> Unit) {
    LyricBehaviorSheet(
        song = song,
        hasPhoneticLyrics = false,
        onDismiss = onDismiss
    )
}

@Composable
fun LyricBehaviorSheet(
    song: SongItem,
    hasTranslationLyrics: Boolean = true,
    hasPhoneticLyrics: Boolean,
    onDismiss: () -> Unit
) {
    val offsetState = remember { LyricBehaviorSheetState(song.userLyricOffsetMs) }
    LyricTranslationSettingLayer(song, offsetState, hasTranslationLyrics, hasPhoneticLyrics, onDismiss)
}

@Composable
private fun LyricTranslationSettingLayer(
    song: SongItem,
    offsetState: LyricBehaviorSheetState,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    onDismiss: () -> Unit
) {
    val settingsRepo = AppContainer.settingsRepo
    val showLyricTranslation by settingsRepo.showLyricTranslationFlow.collectAsStateWithLifecycle(initialValue = true)
    LyricPhoneticSettingLayer(
        song,
        offsetState,
        hasTranslationLyrics,
        hasPhoneticLyrics,
        showLyricTranslation,
        onDismiss
    )
}

@Composable
private fun LyricPhoneticSettingLayer(
    song: SongItem,
    offsetState: LyricBehaviorSheetState,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    showLyricTranslation: Boolean,
    onDismiss: () -> Unit
) {
    val settingsRepo = AppContainer.settingsRepo
    val lyricTranslationUsePhonetic by settingsRepo
        .lyricTranslationUsePhoneticFlow
        .collectAsStateWithLifecycle(initialValue = false)
    LyricBehaviorActionLayer(
        song,
        offsetState,
        hasTranslationLyrics,
        hasPhoneticLyrics,
        showLyricTranslation,
        lyricTranslationUsePhonetic,
        onDismiss
    )
}

@Composable
private fun LyricBehaviorActionLayer(
    song: SongItem,
    offsetState: LyricBehaviorSheetState,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    showLyricTranslation: Boolean,
    lyricTranslationUsePhonetic: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val actions = LyricBehaviorActionOwner(scope, AppContainer.settingsRepo, song, offsetState)
    val phoneticSwitchEnabled = isLyricPhoneticSwitchEnabled(showLyricTranslation, hasPhoneticLyrics)
    val phoneticSwitchChecked = isLyricPhoneticSwitchChecked(
        showLyricTranslation,
        hasPhoneticLyrics,
        lyricTranslationUsePhonetic
    )

    LyricBehaviorContent(
        offsetState = offsetState,
        hasTranslationLyrics = hasTranslationLyrics,
        hasPhoneticLyrics = hasPhoneticLyrics,
        showLyricTranslation = showLyricTranslation,
        phoneticSwitchEnabled = phoneticSwitchEnabled,
        phoneticSwitchChecked = phoneticSwitchChecked,
        onTranslationChange = actions::setTranslation,
        onPhoneticChange = actions::setPhonetic,
        onOffsetCommit = actions::commitOffset,
        onDismiss = onDismiss
    )
}

@Composable
private fun LyricBehaviorContent(
    offsetState: LyricBehaviorSheetState,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    showLyricTranslation: Boolean,
    phoneticSwitchEnabled: Boolean,
    phoneticSwitchChecked: Boolean,
    onTranslationChange: (Boolean) -> Unit,
    onPhoneticChange: (Boolean) -> Unit,
    onOffsetCommit: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetDragBlocker()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(CoreCommonR.string.lyrics_adjust_behavior), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LyricTranslationToggle(
            copy = lyricTranslationToggleCopy(hasTranslationLyrics, hasPhoneticLyrics),
            checked = showLyricTranslation,
            onCheckedChange = onTranslationChange
        )
        LyricPhoneticToggle(
            visible = hasTranslationLyrics,
            translationEnabled = showLyricTranslation,
            hasPhonetic = hasPhoneticLyrics,
            enabled = phoneticSwitchEnabled,
            checked = phoneticSwitchChecked,
            onCheckedChange = onPhoneticChange
        )
        LyricOffsetControls(
            state = offsetState,
            onCommit = onOffsetCommit
        )
        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = onDismiss) {
            Text(stringResource(CoreCommonR.string.action_done))
        }
    }
}

@Composable
private fun LyricTranslationToggle(
    copy: LyricTranslationToggleCopy,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(stringResource(copy.title))
        },
        supportingContent = {
            Text(stringResource(copy.description))
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.lyricToggleModifier(true, checked, onCheckedChange)
    )
}

@Composable
private fun LyricPhoneticToggle(
    visible: Boolean,
    translationEnabled: Boolean,
    hasPhonetic: Boolean,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    if (!visible) return
    LyricPhoneticListItem(translationEnabled, hasPhonetic, enabled, checked, onCheckedChange)
}

@Composable
private fun LyricPhoneticListItem(
    translationEnabled: Boolean,
    hasPhonetic: Boolean,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.lyrics_translation_use_phonetic)) },
        supportingContent = {
            Text(stringResource(lyricPhoneticHint(translationEnabled, hasPhonetic)))
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = lyricPhoneticSwitchAction(hasPhonetic, onCheckedChange),
                enabled = enabled
            )
        },
        modifier = Modifier.lyricToggleModifier(enabled, checked, onCheckedChange)
    )
}

@Composable
private fun LyricOffsetControls(state: LyricBehaviorSheetState, onCommit: () -> Unit) {
    val range = state.sliderRange
    Spacer(Modifier.height(16.dp))
    Text(stringResource(CoreCommonR.string.lyrics_adjust_offset), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        text = formatLyricOffset(state.currentOffset),
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        color = lyricOffsetTextColor(
            state.currentOffset,
            Color(0xFF388E3C),
            MaterialTheme.colorScheme.error,
            LocalContentColor.current
        )
    )
    Text(stringResource(CoreCommonR.string.lyrics_offset_hint), style = MaterialTheme.typography.bodySmall)
    Slider(
        value = state.currentOffset.toFloat(),
        onValueChange = state::setSliderValue,
        onValueChangeFinished = onCommit,
        valueRange = range.min.toFloat()..range.max.toFloat(),
        steps = range.steps
    )
}

@Composable
fun LyricFontSizeSheet(
    currentLyricScale: Float,
    currentTranslationScale: Float,
    onLyricScaleCommit: (Float) -> Unit,
    onTranslationScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    LyricFontSizeStateContent(
        currentLyricScale,
        currentTranslationScale,
        onLyricScaleCommit,
        onTranslationScaleCommit,
        onDismiss
    )
}

@Composable
private fun LyricFontSizeStateContent(
    currentLyricScale: Float,
    currentTranslationScale: Float,
    onLyricScaleCommit: (Float) -> Unit,
    onTranslationScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val state = remember { LyricFontSizeSheetState(currentLyricScale, currentTranslationScale) }
    SyncExternalLyricFontInputs(state, currentLyricScale, currentTranslationScale)
    LyricFontSizeContent(state, onLyricScaleCommit, onTranslationScaleCommit, onDismiss)
}

@Composable
private fun SyncExternalLyricFontInputs(
    state: LyricFontSizeSheetState,
    currentLyricScale: Float,
    currentTranslationScale: Float
) {
    SideEffect(lyricFontInputSyncAction(state, currentLyricScale, currentTranslationScale))
}

internal fun lyricFontInputSyncAction(
    state: LyricFontSizeSheetState,
    lyric: Float,
    translation: Float
): () -> Unit = { state.syncExternalInputs(lyric, translation) }

@Composable
private fun LyricFontSizeContent(
    state: LyricFontSizeSheetState,
    onLyricScaleCommit: (Float) -> Unit,
    onTranslationScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetDragBlocker()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LyricFontSizeHeader()
        LyricScaleSlider(state, onLyricScaleCommit)
        TranslationScaleSlider(state, onTranslationScaleCommit)
        LyricFontSizeDone(state, onLyricScaleCommit, onTranslationScaleCommit, onDismiss)
    }
}

@Composable
private fun LyricFontSizeHeader() {
    Text(stringResource(CoreCommonR.string.lyrics_font_size), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(CoreCommonR.string.settings_lyrics_font_scale_hint),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun LyricScaleSlider(state: LyricFontSizeSheetState, onCommit: (Float) -> Unit) {
    SheetLyricFontScaleSlider(
        title = stringResource(CoreCommonR.string.settings_lyrics_lyric_font_size),
        currentScale = state.lyricValue,
        onScaleChange = state::updateLyricFromSlider,
        onScaleCommit = { onCommit(normalizeLyricFontScale(state.lyricValue)) },
        sampleText = stringResource(CoreCommonR.string.nowplaying_lyrics_sample),
        sampleBaseSizeSp = 18f
    )
}

@Composable
private fun TranslationScaleSlider(state: LyricFontSizeSheetState, onCommit: (Float) -> Unit) {
    SheetLyricFontScaleSlider(
        title = stringResource(CoreCommonR.string.settings_lyrics_translation_font_size),
        currentScale = state.translationValue,
        onScaleChange = state::updateTranslationFromSlider,
        onScaleCommit = { onCommit(normalizeLyricFontScale(state.translationValue)) },
        sampleText = stringResource(CoreCommonR.string.settings_lyrics_translation_sample),
        sampleBaseSizeSp = 14f
    )
}

@Composable
private fun LyricFontSizeDone(
    state: LyricFontSizeSheetState,
    onLyricScaleCommit: (Float) -> Unit,
    onTranslationScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Spacer(Modifier.height(16.dp))
    HapticTextButton(onClick = lyricFontSizeDoneAction(
        state,
        onLyricScaleCommit,
        onTranslationScaleCommit,
        onDismiss
    )) {
        Text(stringResource(CoreCommonR.string.action_done))
    }
}

internal fun lyricFontSizeDoneAction(
    state: LyricFontSizeSheetState,
    onLyricScaleCommit: (Float) -> Unit,
    onTranslationScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
): () -> Unit = {
        onLyricScaleCommit(normalizeLyricFontScale(state.lyricValue))
        onTranslationScaleCommit(normalizeLyricFontScale(state.translationValue))
        onDismiss()
}

@Composable
private fun SheetLyricFontScaleSlider(
    title: String,
    currentScale: Float,
    onScaleChange: (Float) -> Unit,
    onScaleCommit: () -> Unit,
    sampleText: String,
    sampleBaseSizeSp: Float
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${(currentScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Slider(
            value = currentScale,
            onValueChange = onScaleChange,
            onValueChangeFinished = onScaleCommit,
            valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
            steps = 10
        )
        Text(
            text = sampleText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = scaledLyricFontSize(sampleBaseSizeSp, currentScale).sp
        )
    }
}
