package moe.ouom.neriplayer.ui.screen.debug

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import moe.ouom.neriplayer.ui.viewmodel.NeteaseApiProbeViewModel

@Composable
fun NeteaseApiProbeScreen() {
    val context = LocalContext.current

    val vm: NeteaseApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                NeteaseApiProbeViewModel(app)
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
        Text(
            text = "网易云接口探针",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "点击按钮将真实请求接口，并把原始 JSON 复制到剪贴板：\n" +
                    "包含：Account、UserId、我创建的歌单、我收藏的歌单、我喜欢的音乐歌单ID、歌词（33894312）；不包含 liked songs",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.callAllAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("一键调用全部并复制到剪贴板") }

                OutlinedButton(
                    onClick = { vm.callAccountAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅 Account（账户信息）") }

                OutlinedButton(
                    onClick = { vm.callUserIdAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅 UserId（当前登录用户 id）") }

                OutlinedButton(
                    onClick = { vm.callCreatedPlaylistsAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅 我创建的歌单") }

                OutlinedButton(
                    onClick = { vm.callSubscribedPlaylistsAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅 我收藏的歌单") }

                OutlinedButton(
                    onClick = { vm.callLikedPlaylistIdAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅 我喜欢的音乐 歌单ID") }

                OutlinedButton(
                    onClick = { vm.callLyric33894312AndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("仅 歌词（33894312）") }

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
                    text = ui.lastJsonPreview.ifBlank { "（暂无预览，先点上面的按钮试试~）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
