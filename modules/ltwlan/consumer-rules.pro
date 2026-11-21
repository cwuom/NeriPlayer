# 避免 R8 内联保活计时器后生成缺少同步块 catch-all 的 DEX
-keepclassmembers,allowshrinking,allowobfuscation class org.java_websocket.AbstractWebSocket {
    protected void startConnectionLostTimer();
}
