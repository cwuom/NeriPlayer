package moe.ouom.neriplayer.ui.screen.debug

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
 * File: moe.ouom.neriplayer.ui.screen.debug/BiliApiProbeScreen
 * Created: 2025/8/14
 */
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.ui.viewmodel.BiliApiProbeViewModel

@Composable
fun BiliApiProbeScreen() {
    val ctx = LocalContext.current
    val vm: BiliApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                BiliApiProbeViewModel(ctx.applicationContext as Application)
            }
        }
    )

    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "B 站接口探针", style = MaterialTheme.typography.titleLarge)

        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 搜索
                OutlinedTextField(
                    value = ui.keyword,
                    onValueChange = vm::onKeywordChange,
                    label = { Text("搜索关键字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.searchAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("搜索（复制 JSON）") }

                Spacer(Modifier.height(8.dp))

                // 基础信息
                OutlinedTextField(
                    value = ui.bvid,
                    onValueChange = vm::onBvidChange,
                    label = { Text("BV 号（例如：BV1xx...）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.viewByBvidAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("获取封面/简介/统计（复制 JSON）") }

                Spacer(Modifier.height(8.dp))

                // 分P和CID输入
                Text("分P 设置（二选一）", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ui.page,
                        onValueChange = vm::onPageChange,
                        label = { Text("分P（默认1）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ui.cid,
                        onValueChange = vm::onCidChange,
                        label = { Text("CID（数字）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 取流按钮组
                Button(
                    onClick = { vm.playInfoByBvidCidAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank() && (ui.cid.isNotBlank() || ui.page.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val method = if (ui.cid.isNotBlank()) "CID" else "第${ui.page.ifBlank { "1" }}P"
                    Text("获取取流（通过$method，DASH/Dolby/Flac，复制 JSON）")
                }

                Button(
                    onClick = { vm.playInfoByBvidPageAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("通过分P获取取流（第${ui.page.ifBlank { "1" }}P，包含解析的CID）") }

                Button(
                    onClick = { vm.allAudioStreamsByBvidCidAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank() && (ui.cid.isNotBlank() || ui.page.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val method = if (ui.cid.isNotBlank()) "CID" else "第${ui.page.ifBlank { "1" }}P"
                    Text("获取所有音频直链（通过$method，DASH/Dolby/Flac）")
                }

                Button(
                    onClick = { vm.allAudioStreamsByBvidPageAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("通过分P获取所有音频直链（第${ui.page.ifBlank { "1" }}P，包含解析的CID）") }

                Button(
                    onClick = { vm.mp4DurlByBvidCidAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank() && (ui.cid.isNotBlank() || ui.page.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val method = if (ui.cid.isNotBlank()) "CID" else "第${ui.page.ifBlank { "1" }}P"
                    Text("仅 MP4 直链 durl（通过$method）")
                }

                Button(
                    onClick = { vm.mp4DurlByBvidPageAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("通过分P获取 MP4 直链（第${ui.page.ifBlank { "1" }}P，包含解析的CID）") }

                Spacer(Modifier.height(8.dp))

                // 点赞近况
                Button(
                    onClick = { vm.hasLikeByBvidAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("是否近期点过赞（复制 JSON）") }

                Spacer(Modifier.height(8.dp))

                // 收藏夹
                OutlinedTextField(
                    value = ui.upMid,
                    onValueChange = vm::onUpMidChange,
                    label = { Text("UP 主 mid（数字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.createdFavsAndCopy() },
                    enabled = !ui.running && ui.upMid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("获取用户创建的收藏夹（复制 JSON）") }

                OutlinedTextField(
                    value = ui.mediaId,
                    onValueChange = vm::onMediaIdChange,
                    label = { Text("收藏夹 media_id（数字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.favInfoAndCopy() },
                    enabled = !ui.running && ui.mediaId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("收藏夹信息（复制 JSON）") }

                Button(
                    onClick = { vm.favContentsAndCopy() },
                    enabled = !ui.running && ui.mediaId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("收藏夹内容（复制 JSON）") }

                Button(
                    onClick = { vm.playInfoByAvidCidAndCopy() },
                    enabled = !ui.running && ui.bvid.isNotBlank() && (ui.cid.isNotBlank() || ui.page.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("通过 avid+cid/page 获取取流（复制 JSON）") }

                if (ui.running) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("状态：${ui.lastMessage}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = ui.lastJsonPreview.ifBlank { "（暂无预览，上面执行一次试试）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                TextButton(onClick = { vm.clearPreview() }, enabled = !ui.running) {
                    Text("清空预览")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}