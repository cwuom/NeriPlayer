package moe.ouom.neriplayer.ui.screen.tab

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screens/ExploreScreen
 * Created: 2025/8/8
 */

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.ui.viewmodel.ExploreViewModel
import moe.ouom.neriplayer.ui.viewmodel.NeteasePlaylist

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(
    gridState: LazyGridState,
    onPlay: (NeteasePlaylist) -> Unit
) {
    val context = LocalContext.current
    val vm: ExploreViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ExploreViewModel(context.applicationContext as Application) }
        }
    )
    val ui by vm.uiState.collectAsState()

    // 本地记忆选中标签
    val selectedTagState = rememberSaveable { mutableStateOf("全部") }

    // 进入页面默认加载 “全部”
    LaunchedEffect(Unit) {
        if (ui.playlists.isEmpty()) vm.loadHighQuality(selectedTagState.value)
    }

    val query = remember { mutableStateOf("") }
    val tags = listOf(
        "全部",
        "流行","影视原声","华语","怀旧","摇滚","ACG","欧美","清新","夜晚","儿童","民谣","日语","浪漫",
        "学习","韩语","工作","电子","粤语","舞曲","伤感","游戏","下午茶","治愈","说唱","轻音乐",
        "地铁","放松","90后","爵士","驾车","孤独","乡村","运动","感动","网络歌曲","兴奋","R&B/Soul","旅行",
        "KTV","经典","快乐","散步","古典","安静","酒吧","翻唱","民族","吉他","思念","英伦","钢琴","金属","朋克","器乐",
        "蓝调","榜单","雷鬼","00后","世界音乐","拉丁","古风","后摇"
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("探索") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState, // 用外部传进来的 gridState 保存滚动位置
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 搜索框
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query.value,
                    onValueChange = { query.value = it },
                    label = { Text("搜索歌曲 / 艺人 / 专辑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 标签区
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth()) {
                    val display = if (ui.expanded) tags else tags.take(12)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        display.forEach { tag ->
                            val selected = (selectedTagState.value == tag)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (selectedTagState.value != tag) {
                                        selectedTagState.value = tag
                                        vm.loadHighQuality(tag)
                                    }
                                },
                                label = { Text(tag) },
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { vm.toggleExpanded() }) {
                            Text(if (ui.expanded) "收起标签" else "展开更多")
                        }
                    }

                    Spacer(modifier = Modifier.padding(top = 4.dp))
                }
            }

            // 状态区
            if (ui.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (ui.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = ui.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                }
            } else {
                items(
                    ui.playlists,
                    key = { it.id }
                ) { playlist ->
                    PlaylistCard(playlist) { onPlay(playlist) }
                }
            }
        }
    }
}