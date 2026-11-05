package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardSurface
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

// 账号管理只读取配置，进入设置时不请求曲库
@Composable
fun MusicServerSettingsContent() {
    val repository = AppContainer.subsonicRepository
    val profiles by repository.accounts.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<SubsonicProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SubsonicProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loaded = false; error = null
        try { repository.accounts.load(); loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = CoreCommonR.string.server_error_load_accounts }
    }
    MusicServerAccountsContent(
        profiles = profiles,
        loaded = loaded,
        busy = busy,
        error = error,
        onRetry = { reload++ },
        onAdd = { editing = null; showEditor = true },
        onEdit = { editing = it; showEditor = true },
        onRemove = { removing = it }
    )
    if (showEditor) MusicServerAccountEditorDialog(editing, onDismiss = { showEditor = false },
        onSave = { label, address, username, password ->
            val profile = editing
            if (profile == null) repository.addAccount(label, address, username, password)
            else repository.updateAccount(profile, label, address, password)
        })
    removing?.let { profile ->
        MusicServerAccountRemovalDialog(profile, busy = busy,
            onDismiss = { removing = null },
            onConfirm = {
                busy = true; error = null
                scope.launch {
                    try { repository.accounts.remove(profile.id); removing = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = CoreCommonR.string.server_error_remove_account; removing = null }
                    finally { busy = false }
                }
            })
    }
}

@Composable
internal fun MusicServerAccountsContent(
    profiles: List<SubsonicProfile>,
    loaded: Boolean,
    busy: Boolean,
    error: Int?,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SubsonicProfile) -> Unit,
    onRemove: (SubsonicProfile) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (NeriDesign.isClean) 16.dp else 12.dp)) {
        if (!loaded && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            MiuixSettingsInlineMessage(stringResource(it), isSuccess = false)
            if (!loaded) MiuixSettingsTextButton(onClick = onRetry) {
                Text(stringResource(CoreCommonR.string.server_retry))
            }
        }
        SettingsAccountCardSurface(modifier = Modifier.testTag("serverAccountIntro")) {
            Column(Modifier.fillMaxWidth().testTag("serverAccountIntroContent"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (NeriDesign.isClean) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        MusicServerAccountGlyph(Icons.Outlined.LibraryMusic)
                        Text(stringResource(CoreCommonR.string.server_settings_desc), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    HapticButton(enabled = loaded && !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("serverAccountAdd"),
                        shape = NeriDesign.tokens.controlShape, onClick = onAdd) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(CoreCommonR.string.server_add))
                    }
                }
            }
        }
        profiles.forEach { profile ->
            SettingsAccountCardSurface(modifier = Modifier.testTag("serverAccount:${profile.id}")) {
                Column(Modifier.fillMaxWidth().testTag("serverAccountContent:${profile.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MusicServerAccountIdentity(profile)
                    Row(Modifier.fillMaxWidth().testTag("serverAccountAddress:${profile.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(profile.baseUrl, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HapticOutlinedButton(enabled = loaded && !busy, shape = NeriDesign.tokens.controlShape,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("serverAccountEdit:${profile.id}"),
                            onClick = { onEdit(profile) }) {
                            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(CoreCommonR.string.server_edit))
                        }
                        HapticTextButton(enabled = loaded && !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("serverAccountRemove:${profile.id}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            onClick = { onRemove(profile) }) {
                            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(CoreCommonR.string.server_remove))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicServerAccountIdentity(profile: SubsonicProfile) {
    Row(Modifier.fillMaxWidth().testTag("serverAccountIdentity:${profile.id}"),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicServerAccountGlyph(Icons.Outlined.Storage)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.label, Modifier.testTag("serverAccountLabel:${profile.id}"),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(profile.username, Modifier.testTag("serverAccountUser:${profile.id}"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MusicServerAccountGlyph(image: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(image, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
