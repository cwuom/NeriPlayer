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

# 歌词输出协议依赖服务绑定和模型反射
-keep class io.github.proify.lyricon.** { *; }
-keep class com.hchen.superlyricapi.** { *; }
