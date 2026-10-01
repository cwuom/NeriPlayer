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
