# LAN music sync implementation plan

基线为 c8ecee1a。协议 v4、系统音频输出和 Media3 渐进解码仍是实现边界。设计见 [局域网一起听的校时与播放漂移校正](../specs/2026-11-22-lan-music-sync-design.md)。

- 用上游脚本构造校时参照，覆盖探针顺序、脏样本、窗口淘汰、平局和重置。在 LanNtpEstimator 替换 JNI 后运行相同输入，移除发布模块的 Zipline 依赖。
- 在 LAN 专用的 Media3 渲染器接入系统 PCM 呈现时间戳。验证帧和时钟换算、首声截止时间、输出切换和无有效样本时的回退。
- 实现纯 Kotlin 的有界速率控制器，用正负频偏、初始相位差和无效时间序列回归。
- 在 LanMedia3AudioEngine 将已接受的计划、实时房间时差和输出观测相连。按播放代次取消校正，把 nudge、缓冲、暂停、结束和释放纳入同一生命周期。
- 执行模块单测、相关 lint、APK 构建和模拟器的渐进音频测试，记录输出时间戳覆盖和双机声学验证的边界。
