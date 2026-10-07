package moe.ouom.neriplayer.ui.screen.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel

@Composable
fun MusicServerScreen(vm: MusicServerViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val profiles by vm.profiles.collectAsState()
    var query by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    BackHandler(state.album != null) { vm.search("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(CoreCommonR.string.server_my_music), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { managing = true }) { Text(stringResource(CoreCommonR.string.server_manage)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { profile ->
                FilterChip(selected = state.profileId == profile.id, onClick = { query = ""; vm.select(profile.id) },
                    label = { Text(profile.label) })
            }
        }
        if (profiles.isEmpty()) Text(stringResource(CoreCommonR.string.server_empty), Modifier.padding(vertical = 24.dp))
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text(stringResource(CoreCommonR.string.server_search_hint)) })
                TextButton(onClick = { vm.search(query) }) { Text(stringResource(CoreCommonR.string.server_search)) }
            }
            if (state.album != null || state.query.isNotEmpty()) {
                TextButton(onClick = { query = ""; vm.search("") }) { Text(stringResource(CoreCommonR.string.server_back_albums)) }
            }
            state.album?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.load(more = state.hasMore) }) { Text(stringResource(CoreCommonR.string.server_retry)) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = LocalMiniPlayerHeight.current + 24.dp)) {
                if (state.album == null && state.query.isBlank()) {
                    items(state.albums, key = { it.id }) { album ->
                        ServerResourceRow(album.name, album.artist, album.coverUrl) { vm.open(album) }
                    }
                } else {
                    itemsIndexed(state.songs, key = { _, song -> song.audioId ?: song.id.toString() }) { index, song ->
                        ServerResourceRow(song.name, song.artist, song.coverUrl) { PlayerManager.playPlaylist(state.songs, index) }
                    }
                }
                if (!state.loading && state.error == null && state.albums.isEmpty() && state.songs.isEmpty()) {
                    item { Text(stringResource(CoreCommonR.string.server_no_results), Modifier.padding(vertical = 24.dp)) }
                }
                if (state.hasMore) item {
                    TextButton(onClick = { vm.load(more = true) }, enabled = !state.loading) {
                        Text(stringResource(CoreCommonR.string.server_more))
                    }
                }
            }
        }
    }
    if (managing) MusicServerAccountsDialog(vm) { managing = false }
}

@Composable
private fun ServerResourceRow(title: String, subtitle: String, cover: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.size(56.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MusicServerEntry() {
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text(stringResource(CoreCommonR.string.server_my_music)) }
    if (opened) Dialog(onDismissRequest = { opened = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column {
                TextButton(onClick = { opened = false }) { Text(stringResource(CoreCommonR.string.server_close)) }
                MusicServerScreen()
            }
        }
    }
}

@Composable
fun MusicServerAccountsDialog(vm: MusicServerViewModel, onDismiss: () -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(CoreCommonR.string.server_manage)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text(profile.label); Text(profile.username, style = MaterialTheme.typography.bodySmall) }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                try { vm.accounts.remove(profile.id) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "无法移除服务器配置" }
                            }
                        }) { Text(stringResource(CoreCommonR.string.server_remove)) }
                    }
                }
                item { Text("Navidrome / OpenSubsonic", style = MaterialTheme.typography.labelLarge) }
                item { OutlinedTextField(label, { label = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(CoreCommonR.string.server_label)) }) }
                item { OutlinedTextField(address, { address = it }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text(stringResource(CoreCommonR.string.server_address)) }) }
                item { OutlinedTextField(username, { username = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(CoreCommonR.string.server_username)) }) }
                item { OutlinedTextField(password, { password = it }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(CoreCommonR.string.server_password)) }) }
                item { Text(stringResource(CoreCommonR.string.server_address_help), style = MaterialTheme.typography.bodySmall) }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        vm.add(label, address, username, password)
                        password = ""; address = ""; username = ""; label = ""
                    } catch (failure: Exception) {
                        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                        error = MusicServerViewModel.userError(failure, accountInput = true)
                    } finally { busy = false }
                }
            }) { Text(stringResource(CoreCommonR.string.server_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(CoreCommonR.string.server_close)) } })
}
