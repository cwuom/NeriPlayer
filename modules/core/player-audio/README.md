# PCM 处理

`:core:player-audio` 提供声道平衡、音量归一化、PCM 电平分析及音频可视化信号。`audio/processing` 负责样本计算与 Media3 AudioProcessor，`audio/reactive` 负责可视化状态发布

模块不读取 `PlayerManager` 或 `AppContainer`，不管理平台音效、USB 会话或服务。这些集成属于 `:feature:player`；跨模块音频状态使用 `:data:model`

PCM 修改应使用真实 ByteBuffer 验证样本格式、字节序、尾部不完整样本和配置切换。共享 CRAP scope 自动包含这些职责包中的新文件和子目录；任何受管方法的分数大于 9 都会使门禁失败

```bash
./gradlew :core:player-audio:verifyCrap :core:player-audio:lintDebug
```

模块布局和依赖方向见仓库根目录 [README](../../../README.md)
