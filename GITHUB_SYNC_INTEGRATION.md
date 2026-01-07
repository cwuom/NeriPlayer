# GitHub自动备份同步功能 - 集成指南

## 📦 已实现的功能

### 1. 核心组件
- ✅ `SecureTokenStorage` - Token 加密存储
- ✅ `GitHubApiClient` - GitHub API 客户端
- ✅ `SyncDataModels` - 同步数据模型
- ✅ `GitHubSyncManager` - 三路合并同步管理器
- ✅ `GitHubSyncWorker` - WorkManager 后台同步
- ✅ `GitHubSyncViewModel` - ViewModel

### 2. 功能特性
- ✅ Token加密存储 (Android Keystore + EncryptedSharedPreferences)
- ✅ 自动创建私有仓库或使用现有仓库
- ✅ 三路合并算法,自动解决冲突
- ✅ 智能同步 (延迟5秒 + 定期每小时)
- ✅ 支持桌面端和移动端互相同步
- ✅ 冲突自动合并 (添加合并, 删除优先, 修改取最新)

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

- Token 使用 Android Keystore 加密存储
- 仓库默认为私有
- 所有网络请求使用 HTTPS
- 不会明文存储任何敏感信息

## 🎯 冲突解决策略

- **添加操作**: 两端都保留 (合并)
- **删除操作**: 任一端删除则删除
- **修改操作**: 最新时间戳优先
- **歌单重命名**: 最新时间戳优先

## 📱 桌面端支持

桌面端只需要实现相同的数据结构和同步逻辑, 使用相同的GitHub仓库即可实现跨平台同步。

## ⚠️ 注意事项

1. 需要在AndroidManifest.xml中添加网络权限(应该已有)
2. 需要在Application类中初始化WorkManager(如果还没有)
3. Token需要 `repo` 权限才能创建私有仓库
4. 建议在用户首次使用时显示使用说明

## 🐛 调试

查看日志标签:
- `SecureTokenStorage`
- `GitHubApiClient`
- `GitHubSyncManager`
- `GitHubSyncWorker`
