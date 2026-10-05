# Hidden framework interfaces are supplied by Android at runtime. The hidden-api
# module provides compile-time declarations only, so these types are absent from
# the APK and the public SDK's android.jar used by R8.
-dontwarn android.net.IConnectivityManager
-dontwarn android.net.IConnectivityManager$Stub
-dontwarn android.os.INetworkManagementService
-dontwarn android.os.INetworkManagementService$Stub

# native 静态绑定要求 USB 桥接类名和方法名保持稳定
-keepclasseswithmembernames,includedescriptorclasses class moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeBridge {
    native <methods>;
}

# FFmpeg 从 native 回调私有缓冲区扩容方法
-keep,allowoptimization,allowobfuscation class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(
        androidx.media3.decoder.SimpleDecoderOutputBuffer, int
    );
}
