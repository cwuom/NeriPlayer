package moe.ouom.neriplayer.ui.screen.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel

@Composable
fun MusicServerScreen(vm: MusicServerViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val location = vm.locationKey
    val listState = remember(location) {
        val (index, offset) = vm.listPosition(location)
        LazyListState(index, offset)
    }
    DisposableEffect(location, listState) {
        onDispose { vm.saveListPosition(location, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    BackHandler(state.album != null || state.query.isNotBlank()) { vm.back() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(CoreCommonR.string.server_my_music), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = vm::refresh, enabled = !state.loading && state.profileId != null) {
                Text(stringResource(CoreCommonR.string.server_refresh))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { profile ->
                FilterChip(selected = state.profileId == profile.id, onClick = { vm.select(profile.id) },
                    label = { Text(profile.label) })
            }
        }
        if (state.accountsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (state.accountsError != null) {
            Text(stringResource(state.accountsError!!), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::reloadAccounts) { Text(stringResource(CoreCommonR.string.server_retry)) }
        }
        else if (profiles.isEmpty()) Text(stringResource(CoreCommonR.string.server_empty), Modifier.padding(vertical = 24.dp))
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.inputQuery, onValueChange = vm::editQuery, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text(stringResource(CoreCommonR.string.server_search_hint)) })
                TextButton(onClick = { vm.search(state.inputQuery) }) { Text(stringResource(CoreCommonR.string.server_search)) }
            }
            if (state.album != null || state.query.isNotEmpty()) {
                TextButton(onClick = vm::back) { Text(stringResource(CoreCommonR.string.server_back_albums)) }
            }
            state.album?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { message ->
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::retry) { Text(stringResource(CoreCommonR.string.server_retry)) }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = LocalMiniPlayerHeight.current + 24.dp)) {
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
