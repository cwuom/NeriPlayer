# 保留注解
-keepattributes *Annotation*

# 保留 Parcelable 的实现者
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Compose 需要保留其运行时的一些关键部分
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.runtime.internal.** { *; }
-keep class androidx.compose.runtime.saveable.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Compose 手势检测相关
-keep class androidx.compose.foundation.gestures.** { *; }
-keep class androidx.compose.ui.input.pointer.** { *; }
-keep class androidx.compose.ui.input.pointer.PointerInputScope { *; }
-keep class androidx.compose.ui.input.pointer.PointerInputChange { *; }
-keepclassmembers class androidx.compose.ui.input.pointer.PointerInputScope {
    *;
}

# OkHttp 内部使用了反射，需要保留一些类
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-keep class coil.** { *; }
-keep class coil.decode.* { *; }
-keep class coil.fetch.* { *; }
-keep class coil.memory.* { *; }
-keep class coil.request.* { *; }
-keep class coil.target.* { *; }
-keep class coil.transition.* { *; }
-keep class coil.util.* { *; }
-dontwarn coil.util.CoilUtils

# 防止混淆数据模型类
-keep class moe.ouom.neriplayer.data.** { *; }
-keep class moe.ouom.neriplayer.ui.viewmodel.** { *; }

# Gson 内部使用反射来序列化和反序列化
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.Gson { *; }

# 对于泛型，需要保留字段名
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlinx Serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}

# Media3 需要一些规则来确保所有解码器和渲染器正常工作
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Reorderable (拖拽排序)
-keep class org.burnoutcrew.reorderable.** { *; }

# Accompanist (Compose 辅助库)
-keep class com.google.accompanist.** { *; }

-keep class moe.ouom.neriplayer.** { *; }

# WorkManager - 保留Worker类
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(...);
}
-keep class androidx.work.** { *; }

# Security Crypto - 保留加密相关类
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Tink 可选依赖 - 忽略未使用的 Google API Client 和 Joda Time
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**

# GitHub 同步数据模型 - 确保Gson序列化正常
-keep class moe.ouom.neriplayer.data.github.** { *; }
-keepclassmembers class moe.ouom.neriplayer.data.github.** {
    <fields>;
    <init>(...);
}