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