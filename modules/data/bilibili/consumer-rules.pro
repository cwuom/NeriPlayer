# 历史缓存通过 Gson 按字段名读取，模型移动后仍需保留旧 JSON 键
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.platform.bili.cache.archive.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.platform.bili.cache.favorite.model.** {
    <fields>;
    <init>(...);
}
