# LAN 产品接入验证记录

日期：2026-10-28；基线 `8eee851c`；分支 `codex/lan-beatsync-restart`。这是已接入正式 Debug 应用的首版，双机真实声音验收尚未完成。

## 实现与用户修订

- Java-WebSocket 手机服务端 + OkHttp 客户端，主控也通过回环连接；NSD 发现与手动加入。
- BeatSync 固定原码的 NTP，16 样本窗口、定时播放、装载代次校验、权限与连接恢复。
- 播放页和设置的一起听页面直接提供创建、加入按钮；已连接改为房间管理。
- 根据用户反馈改为系统 `STREAM_MUSIC`，加入上报当前值，主控统一设置与成员独立设置不相乘；本机音量键回传。未将播放器增益视为系统音量。
- 资源、依赖组装、播放器适配和协议模型按 CONTRIBUTING 分层；中英文 README / CONTRIBUTING、许可、搜索与模块登记同步更新。

## 已运行

- `:app:assembleDebug`：成功。最新 APK 64778868 bytes，SHA-256 `283749d9ff282b326264b20995ce7548c2b6a2ae261fd383f07e77f48097ed2b`。
- `:ltwlan:verifyCrap`、`:ltwlan:lintDebug`：成功；不改变门禁阈值，不缩小范围。
- `:app:verifyDomainDependencies`：成功，0 violations。
- `module_boundaries.py`：成功。
- `:listentogether:verifyCrap`：成功，云端原行为测试通过。
- 播放器 LAN 路由及 ListenTogether 相关回归：通过。
- 设置搜索、创建/加入按钮、行数约束的 app 测试：通过。
- `:app:lintDebug`：成功，无 error，现有项目报告 50 warnings / 2 hints；此后仅修改 LAN 核心的结束重播/装载中暂停边界，并重跑 LAN lint、测试及构建。
- 质量工具的 Windows javap 中文输出编码问题：统一 UTF-8，增加 Unicode 常量回归用例；工具测试与实际域检查通过。

当前测试报告计数：

```json
{
  "LAN": {
    "tests": 32,
    "failures": 0,
    "errors": 0
  },
  "Cloud": {
    "tests": 473,
    "failures": 0,
    "errors": 0
  },
  "Playback focused": {
    "tests": 60,
    "failures": 0,
    "errors": 0
  },
  "Settings generated": {
    "tests": 30,
    "failures": 2,
    "errors": 0
  }
}
```

`AutoSettingsGeneratedTest` 的新增设置元数据数量断言已更新。该套件仍有两项失败：`highRefreshRateStartsEnabledAndRetainsAnExplicitOptOut` 与 `homeBilibiliVisibilityDefaultsOnAndRetainsAnExplicitOptOut`，均在 Windows DataStore 临时文件 rename 时抛出 IOException。没有删除测试、忽略错误或宣称全仓库测试通过；原因尚未彻底定位。

## 设备验证与部署

ADB 识别两台设备（Android 17 / Android 16）。Android 17 上 `LanSessionDeviceTest` 成功，使用真实 Java-WebSocket、OkHttp、QuickJS 和合成播放端口，验证房间建/加、NTP、越权拒绝、授权 seek、音量事件和资源释放。这项测试发生在系统媒体音量调整前，不代表新的 AudioManager 路径已真机验收。

正式 Debug 应用曾在 Android 17 界面显示已连接、RTT 约 17ms 与当前歌曲；这是界面/会话证据，不能证明声音同步精度。最终按钮/系统媒体音量版本已通过 `adb push` + `adb shell pm install -r -t` 覆盖安装到两台设备，保留原有 Debug 数据，没有要求手机端安装确认。

独立 `moe.ouom.neriplayer.lanltw.test` 已从两台设备卸载；推送到 `/data/local/tmp` 的本次 APK 和 UI dump 已删除。本次启动的只读模拟器已关闭。原来存在的正式版 `moe.ouom.neriplayer` 保留，当前 Debug 版本保留。

## 未完成的验收

最终联调时两台手机处于锁屏，未尝试绕过锁屏。仍需解锁后验证：

1. 播放页/设置页直接按钮的实际布局和触摸，手机与平板/横屏适配。
2. 两台物理设备的 NSD 发现、加入、实际音源解析、暂停、seek、下一首。
3. 系统媒体音量滑块及硬件音量键双向同步，加入不突增音量。
4. 后台与熄屏连续播放、断线重连；至少 10 分钟实际录音输出偏差。
5. USB/本地曲目不支持时反馈、长暂停服务回收、输出路由变化。

本轮没有运行完整 `:app:verifyCrap` / `verifyModularization`、完整全库单测或 Release 构建；没有提交 commit 或 PR。


后续修订：音频后端和主控链接共享现已更新，见 [Web Audio 与音源共享验证](lan-web-audio-sharing-validation.md)。本文此前各端独立解析的行为不再是当前实现。
