# 运行时注解和泛型签名要保留，不然 Gson 和内联 serializer 很容易踩坑
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 只保留 Parcelable 约定需要的 CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Compose 编译器生成的调用链会依赖这些方法签名
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Gson 的泛型 token 和入口类保留，真正的模型边界再单独收窄
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.Gson { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.data.** {
    <fields>;
    <init>(...);
}
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.core.player.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers,allowoptimization class moe.ouom.neriplayer.ui.viewmodel.tab.** {
    <fields>;
    <init>(...);
}

# kotlinx.serialization 主要靠生成代码，补一个 Companion serializer 兜底更稳
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer {
    static <fields>;
}

# WorkManager 会把 worker 类名落库，类名不能被改
-keepnames class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 当前 JNI 还是静态绑定，桥接类名和 native 方法名都得稳定
-keepclasseswithmembernames,includedescriptorclasses class moe.ouom.neriplayer.** {
    native <methods>;
}

# FFmpeg decoder 会从 native 回调打到这个私有扩容路径
-keep,allowoptimization,allowobfuscation class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(
        androidx.media3.decoder.SimpleDecoderOutputBuffer, int
    );
}

# Lyricon 走 service 绑定和模型反射，这块先保守一点
-keep class io.github.proify.lyricon.** { *; }
-dontwarn io.github.proify.lyricon.**

# SuperLyricApi 在系统服务兼容分支上比较脆，先别动它
-keep class com.hchen.superlyricapi.** { *; }

# 这些库有自己的 consumer rules，这里只压掉可选依赖告警
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil.util.CoilUtils
-dontwarn androidx.media3.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-dontwarn android.os.ServiceManager

# 给 R8 更多操作空间，让真正的 shrink 生效
-allowaccessmodification
-repackageclasses ''
