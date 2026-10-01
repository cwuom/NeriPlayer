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
