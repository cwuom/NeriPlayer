package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.lyrics.offset.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.lyrics.offset.MIN_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.lyrics.offset.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.lyrics.offset.normalizeLyricDefaultOffsetMs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.theme.NeriDesign
import kotlin.math.roundToLong

@Composable
internal fun LyricsOffsetSliderListItem(
    targetId: String,
    title: String,
    description: String,
    offsetMs: Long,
    defaultOffsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    var pendingOffset by remember(offsetMs) { mutableLongStateOf(offsetMs) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = NeriDesign.tokens.cardShape
    val editLabel = stringResource(AppR.string.lyric_offset_input_action)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (NeriDesign.isClean) colors.surfaceContainer else colors.surfaceContainerLow)
            .settingsHighlightTarget(targetId, highlightTargetId, highlightPulse, onHighlightFinished)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            IconButton(
                enabled = pendingOffset != defaultOffsetMs,
                onClick = {
                    pendingOffset = defaultOffsetMs
                    onOffsetChange(defaultOffsetMs)
                }
            ) {
                Icon(
                    Icons.Rounded.RestartAlt,
                    contentDescription = stringResource(CoreCommonR.string.settings_lyrics_offset_reset)
                )
            }
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${if (pendingOffset > 0L) "+" else ""}$pendingOffset ms",
                modifier = Modifier
                    .clip(NeriDesign.tokens.chipShape)
                    .clickable(role = Role.Button, onClickLabel = editLabel) { editing = true }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                textDecoration = TextDecoration.Underline,
                color = colors.primary
            )
        }
        MiuixSettingsSlider(
            value = pendingOffset.toFloat(),
            onValueChange = { pendingOffset = normalizeLyricDefaultOffsetMs(it.roundToLong()) },
            onValueChangeFinished = { onOffsetChange(pendingOffset) },
            valueRange = MIN_LYRIC_DEFAULT_OFFSET_MS.toFloat()..MAX_LYRIC_DEFAULT_OFFSET_MS.toFloat(),
            steps = ((MAX_LYRIC_DEFAULT_OFFSET_MS - MIN_LYRIC_DEFAULT_OFFSET_MS) / LYRIC_DEFAULT_OFFSET_STEP_MS).toInt() - 1,
            showTicks = false,
            modifier = Modifier.semantics { contentDescription = title }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(AppR.string.lyric_offset_delay_limit),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            Text(
                stringResource(AppR.string.lyric_offset_advance_limit),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
    if (editing) {
        LyricOffsetInputDialog(
            title = title,
            offsetMs = pendingOffset,
            onDismiss = { editing = false },
            onConfirm = {
                pendingOffset = it
                editing = false
                onOffsetChange(it)
            }
        )
    }
}

@Composable
private fun LyricOffsetInputDialog(
    title: String,
    offsetMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = offsetMs.toString()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val parsed = field.text.trim().toLongOrNull()
        ?.takeIf { it in MIN_LYRIC_DEFAULT_OFFSET_MS..MAX_LYRIC_DEFAULT_OFFSET_MS }
        ?.let(::normalizeLyricDefaultOffsetMs)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiuixSettingsTextField(
                        value = field,
                        onValueChange = { field = it },
                        modifier = Modifier.weight(1f).testTag("lyric-offset-input").focusRequester(focusRequester),
                        label = { Text("ms") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) })
                    )
                    MiuixSettingsTextButton(onClick = {
                        val text = field.text.trim().let {
                            if (it.startsWith("-")) it.removePrefix("-") else "-${it.removePrefix("+")}"
                        }
                        field = TextFieldValue(text, selection = TextRange(text.length))
                    }) {
                        Text("±")
                    }
                }
                Text(
                    stringResource(if (parsed == null) AppR.string.lyric_offset_input_invalid else AppR.string.lyric_offset_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}
