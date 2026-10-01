# 同步状态存储

`:data:sync-store` 管理 GitHub/WebDAV 加密凭据、配置与同步偏好，以及共享设备 ID、因果计数器、本地变更版本和删除记录。

`secure` 负责加密偏好打开与损坏恢复；`state` 按配置、设备身份、版本和各类删除记录划分职责；`github` 提供兼容存储入口；`webdav` 管理远端配置；`preferences` 管理播放历史同步间隔。

存储文件名和键名保持兼容，迁移模块不会重建用户身份。设备 ID 的读取与首次创建共享因果计数器锁；删除记录和本地变更版本在同一个 editor 中提交，提交失败向调用方报告。配置恢复保留设备身份与同步版本。

```bash
./gradlew :data:sync-store:verifyCrap :data:sync-store:lintDebug
```

新增源码自动进入整文件复杂度门禁，任意方法 CRAP 大于 9 即失败。JVM 测试验证提交边界与状态策略，Android 加密文件和进程重启行为仍由设备测试验证。
