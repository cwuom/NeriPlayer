# 历史缓存通过 Gson 按字段名读取，模型移动后仍需保留旧 JSON 键
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.model.bilibili.cache.archive.** {
    <fields>;
    <init>(...);
}
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.model.bilibili.cache.favorite.** {
    <fields>;
    <init>(...);
}
# 播放状态由 Gson 持久化，字段名和构造器跟随模型迁移
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.model.playback.** {
    <fields>;
    <init>(...);
}
# 歌词编辑状态的持久化载荷使用稳定字段名，升级后需能读回恢复标记
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.model.lyrics.LyricSyncPersistence {
    <fields>;
    <init>(...);
}
# 歌单使用记录的删除观察证明必须能在混淆后的版本继续读回
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.model.stats.UsageEntry {
    <fields>;
    <init>(...);
}
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.sync.model.SyncCausalToken {
    <fields>;
    <init>(...);
}
