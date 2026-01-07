# GitHub自动备份同步功能 - 集成指南

## 📦 已实现的功能

### 1. 核心组件
- ✅ `SecureTokenStorage` - Token加密存储
- ✅ `GitHubApiClient` - GitHub API客户端
- ✅ `SyncDataModels` - 同步数据模型
- ✅ `GitHubSyncManager` - 三路合并同步管理器
- ✅ `GitHubSyncWorker` - WorkManager后台同步
- ✅ `GitHubSyncViewModel` - ViewModel

### 2. 功能特性
- ✅ Token加密存储(Android Keystore + EncryptedSharedPreferences)
- ✅ 自动创建私有仓库或使用现有仓库
- ✅ 三路合并算法,自动解决冲突
- ✅ 智能同步(延迟5秒+定期每小时)
- ✅ 支持桌面端和移动端互相同步
- ✅ 冲突自动合并(添加合并,删除优先,修改取最新)

## 🔧 需要添加的依赖

在 `app/build.gradle.kts` 中添加:

```kotlin
dependencies {
    // 已有的依赖...

    // Security - 加密存储
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager - 后台同步
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp - 网络请求(如果还没有)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson - JSON序列化(如果还没有)
    implementation("com.google.code.gson:gson:2.10.1")
}
```

## 📝 集成到设置界面

### 在 `SettingsScreen.kt` 中添加GitHub同步UI

在"备份与恢复"区域后面添加以下代码:

```kotlin
// 在SettingsScreen函数参数中添加:
fun SettingsScreen(
    // ... 现有参数
    onNavigateToGitHubSync: () -> Unit = {}
)

// 在LazyColumn中,备份与恢复区域后添加:

// GitHub 自动同步
item {
    ExpandableHeader(
        icon = Icons.Outlined.CloudSync, // 需要导入
        title = "GitHub 自动同步",
        subtitleCollapsed = "自动备份到 GitHub 私有仓库",
        subtitleExpanded = "收起",
        expanded = githubSyncExpanded,
        onToggle = { githubSyncExpanded = !githubSyncExpanded },
        arrowRotation = githubSyncArrowRotation
    )
}

item {
    AnimatedVisibility(
        visible = githubSyncExpanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
        ) {
            val githubVm: GitHubSyncViewModel = viewModel()
            val githubState by githubVm.uiState.collectAsState()

            LaunchedEffect(Unit) {
                githubVm.initialize(context)
            }

            if (!githubState.isConfigured) {
                // 未配置状态
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "配置 GitHub",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = { Text("配置 GitHub 同步") },
                    supportingContent = { Text("点击配置 Token 和仓库") },
                    modifier = Modifier.clickable {
                        showGitHubConfigDialog = true
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            } else {
                // 已配置状态
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = "已配置",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = { Text("GitHub 同步已配置") },
                    supportingContent = {
                        Text("仓库: ${githubState.repoOwner}/${githubState.repoName}")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                // 自动同步开关
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = "自动同步",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("自动同步") },
                    supportingContent = { Text("修改后自动同步到 GitHub") },
                    trailingContent = {
                        Switch(
                            checked = githubState.autoSyncEnabled,
                            onCheckedChange = { githubVm.toggleAutoSync(context, it) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                // 立即同步按钮
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.CloudUpload,
                            contentDescription = "立即同步",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = { Text("立即同步") },
                    supportingContent = {
                        if (githubState.lastSyncTime > 0) {
                            Text("上次同步: ${formatSyncTime(githubState.lastSyncTime)}")
                        } else {
                            Text("尚未同步")
                        }
                    },
                    trailingContent = {
                        if (githubState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            OutlinedButton(onClick = { githubVm.performSync(context) }) {
                                Text("同步")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                // 同步结果
                githubState.syncResult?.let { result ->
                    ListItem(
                        headlineContent = { Text("同步结果") },
                        supportingContent = {
                            Text(buildString {
                                append("新增: ${result.playlistsAdded} 个歌单\n")
                                append("更新: ${result.playlistsUpdated} 个歌单\n")
                                append("新增歌曲: ${result.songsAdded} 首")
                            })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }

                // 清除配置
                TextButton(onClick = {
                    showClearGitHubConfigDialog = true
                }) {
                    Text("清除 GitHub 配置", color = MaterialTheme.colorScheme.error)
                }
            }

            // 错误消息
            githubState.errorMessage?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { githubVm.clearMessages() }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }
            }

            // 成功消息
            githubState.successMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { githubVm.clearMessages() }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }
            }
        }
    }
}
```

### GitHub配置对话框

```kotlin
// 在SettingsScreen函数中添加状态变量:
var showGitHubConfigDialog by remember { mutableStateOf(false) }
var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
var githubToken by remember { mutableStateOf("") }
var githubRepoName by remember { mutableStateOf("neriplayer-backup") }
var useExistingRepo by remember { mutableStateOf(false) }
var existingRepoName by remember { mutableStateOf("") }

// 在Scaffold外部添加对话框:
if (showGitHubConfigDialog) {
    val githubVm: GitHubSyncViewModel = viewModel()
    val githubState by githubVm.uiState.collectAsState()

    AlertDialog(
        onDismissRequest = { showGitHubConfigDialog = false },
        title = { Text("配置 GitHub 同步") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "步骤1: 输入 GitHub Personal Access Token",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("GitHub Token") },
                    placeholder = { Text("ghp_xxxxxxxxxxxx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "需要权限: repo (完整仓库访问)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/settings/tokens/new?scopes=repo&description=NeriPlayer%20Backup".toUri()
                        )
                        context.startActivity(intent)
                    }
                ) {
                    Text("在 GitHub 创建 Token")
                }

                if (githubState.tokenValid) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "步骤2: 选择仓库",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !useExistingRepo,
                            onClick = { useExistingRepo = false }
                        )
                        Text("创建新仓库")
                    }

                    if (!useExistingRepo) {
                        OutlinedTextField(
                            value = githubRepoName,
                            onValueChange = { githubRepoName = it },
                            label = { Text("仓库名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = useExistingRepo,
                            onClick = { useExistingRepo = true }
                        )
                        Text("使用现有仓库")
                    }

                    if (useExistingRepo) {
                        OutlinedTextField(
                            value = existingRepoName,
                            onValueChange = { existingRepoName = it },
                            label = { Text("仓库全名") },
                            placeholder = { Text("username/repo-name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!githubState.tokenValid) {
                HapticButton(
                    onClick = { githubVm.validateToken(context, githubToken) },
                    enabled = githubToken.isNotBlank() && !githubState.isValidating
                ) {
                    if (githubState.isValidating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("验证Token")
                }
            } else {
                HapticButton(
                    onClick = {
                        if (useExistingRepo) {
                            githubVm.useExistingRepository(context, existingRepoName)
                        } else {
                            githubVm.createRepository(context, githubRepoName)
                        }
                        showGitHubConfigDialog = false
                    },
                    enabled = !githubState.isCreatingRepo && !githubState.isCheckingRepo
                ) {
                    if (githubState.isCreatingRepo || githubState.isCheckingRepo) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("完成")
                }
            }
        },
        dismissButton = {
            HapticTextButton(onClick = { showGitHubConfigDialog = false }) {
                Text("取消")
            }
        }
    )
}

if (showClearGitHubConfigDialog) {
    val githubVm: GitHubSyncViewModel = viewModel()

    AlertDialog(
        onDismissRequest = { showClearGitHubConfigDialog = false },
        title = { Text("清除 GitHub 配置") },
        text = { Text("这将清除所有GitHub同步配置,包括Token和仓库信息。本地数据不会被删除。") },
        confirmButton = {
            HapticTextButton(
                onClick = {
                    githubVm.clearConfiguration(context)
                    showClearGitHubConfigDialog = false
                }
            ) {
                Text("确认清除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            HapticTextButton(onClick = { showClearGitHubConfigDialog = false }) {
                Text("取消")
            }
        }
    )
}
```

### 辅助函数

```kotlin
// 在SettingsScreen.kt文件末尾添加:

/**
 * 格式化同步时间
 */
private fun formatSyncTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        else -> "${diff / 86400_000}天前"
    }
}
```

## 🚀 使用流程

1. **用户配置**:
   - 点击"配置 GitHub 同步"
   - 输入 GitHub Personal Access Token
   - 选择创建新仓库或使用现有仓库
   - 完成配置

2. **自动同步**:
   - 开启"自动同步"开关
   - 修改歌单后 5 秒自动同步
   - 每小时定期同步
   - 应用启动时自动同步

3. **手动同步**:
   - 点击"立即同步"按钮
   - 查看同步结果

## 🔒 安全性

- Token使用Android Keystore加密存储
- 仓库默认为私有
- 所有网络请求使用HTTPS
- 不会明文存储任何敏感信息

## 🎯 冲突解决策略

- **添加操作**: 两端都保留(合并)
- **删除操作**: 任一端删除则删除
- **修改操作**: 最新时间戳优先
- **歌单重命名**: 最新时间戳优先

## 📱 桌面端支持

桌面端只需要实现相同的数据结构和同步逻辑,使用相同的GitHub仓库即可实现跨平台同步。

## ⚠️ 注意事项

1. 需要在AndroidManifest.xml中添加网络权限(应该已有)
2. 需要在Application类中初始化WorkManager(如果还没有)
3. Token需要`repo`权限才能创建私有仓库
4. 建议在用户首次使用时显示使用说明

## 🐛 调试

查看日志标签:
- `SecureTokenStorage`
- `GitHubApiClient`
- `GitHubSyncManager`
- `GitHubSyncWorker`
