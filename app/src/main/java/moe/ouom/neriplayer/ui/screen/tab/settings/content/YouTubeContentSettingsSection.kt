package moe.ouom.neriplayer.ui.screen.tab.settings.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.Collator
import java.util.Locale
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.updateYouTubeContentPreferences
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

@Composable
internal fun YouTubeLocaleSettingsSection(
    enabled: Boolean,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: () -> Unit
) {
    @Composable
    fun rowModifier(id: String) =
        Modifier.settingsHighlightTarget("manual:$id", highlightTargetId, highlightPulse, onHighlightFinished)

    val prefs by YouTubeContentSettings.preferences.collectAsStateWithLifecycle()
    val displayLocale = LocalConfiguration.current.locales[0]
    val system = YouTubeContentSettings.systemLocale()
    val defaults = prefs.copy(languageTag = "", countryCode = "").resolveLocale(system)
    val languages = remember(displayLocale) {
        localizedChoices(YouTubeContentSettings.languageTags, displayLocale) {
            Locale.forLanguageTag(it).getDisplayName(displayLocale)
        }
    }
    val countries = remember(displayLocale) {
        localizedChoices(YouTubeContentSettings.countryCodes, displayLocale) {
            Locale.Builder().setRegion(it).build().getDisplayCountry(displayLocale)
        }
    }
    Column {
        ContentChoice(
            modifier = rowModifier("youtube_content_language"),
            enabled = enabled,
            title = stringResource(R.string.youtube_content_language),
            selected = prefs.languageTag,
            choices = listOf("" to stringResource(R.string.youtube_content_automatic,
                Locale.forLanguageTag(defaults.hl).getDisplayName(displayLocale))) + languages
        ) { code -> updateContentPreferences { it.copy(languageTag = code) } }
        ContentChoice(
            modifier = rowModifier("youtube_content_country"),
            enabled = enabled,
            title = stringResource(R.string.youtube_content_country),
            selected = prefs.countryCode,
            choices = listOf("" to stringResource(R.string.youtube_content_automatic,
                Locale.Builder().setRegion(defaults.gl).build().getDisplayCountry(displayLocale))) + countries
        ) { code -> updateContentPreferences { it.copy(countryCode = code) } }
    }
}

private fun updateContentPreferences(change: (YouTubeContentPreferences) -> YouTubeContentPreferences) {
    AppContainer.launchBackgroundIo { AppContainer.applicationContext.updateYouTubeContentPreferences(change) }
}

@Composable
internal fun YouTubeContentSettingsSection(
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: () -> Unit
) {
    @Composable
    fun rowModifier(id: String) =
        Modifier.settingsHighlightTarget("manual:$id", highlightTargetId, highlightPulse, onHighlightFinished)

    val prefs by YouTubeContentSettings.preferences.collectAsStateWithLifecycle()
    Column {
        ContentToggle(rowModifier("youtube_content_hide_explicit"), stringResource(R.string.youtube_content_hide_explicit), prefs.hideExplicit) { hide ->
            updateContentPreferences { it.copy(hideExplicit = hide) }
        }
        ContentToggle(rowModifier("youtube_content_hide_videos"), stringResource(R.string.youtube_content_hide_videos), prefs.hideMusicVideos) { hide ->
            updateContentPreferences { it.copy(hideMusicVideos = hide) }
        }
    }
}

private fun localizedChoices(codes: List<String>, displayLocale: Locale, label: (String) -> String): List<Pair<String, String>> {
    val collator = Collator.getInstance(displayLocale)
    return codes.map { it to label(it) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
}

// 短列表直接选更快，只有语言、地区这类长列表才需要搜索框
private const val SEARCHABLE_CHOICE_THRESHOLD = 3

@Composable
internal fun ContentChoice(
    modifier: Modifier,
    title: String,
    selected: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var open by rememberSaveable(enabled) { mutableStateOf(false) }
    val textColor = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        modifier = if (enabled) modifier.settingsItemClickable { open = true }
            else modifier.semantics(mergeDescendants = true) { disabled() },
        headlineContent = { Text(title, color = textColor) },
        supportingContent = { Text(choices.first { it.first == selected }.second, color = textColor) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
    if (enabled && open) {
        var query by rememberSaveable { mutableStateOf("") }
        val visibleChoices = choices.filter { (code, label) ->
            label.contains(query.trim(), ignoreCase = true) || code.contains(query.trim(), ignoreCase = true)
        }
        MiuixSettingsDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (choices.size > SEARCHABLE_CHOICE_THRESHOLD) {
                        item {
                            MiuixSettingsTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(stringResource(CommonR.string.action_search)) },
                                singleLine = true,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    items(visibleChoices, key = { it.first }) { (code, label) ->
                        MiuixSettingsChoiceRow(title = label, selected = code == selected, onClick = {
                            onSelect(code)
                            open = false
                        })
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = { open = false }) { Text(stringResource(CommonR.string.action_close)) }
            }
        )
    }
}

@Composable
private fun ContentToggle(modifier: Modifier, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(title) },
        trailingContent = { MiuixSettingsSwitch(checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
