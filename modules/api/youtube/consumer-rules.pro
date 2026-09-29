# NewPipe extractor 用 Mozilla Rhino 求值 YouTube player.js。Rhino 通过固定类名反射
# 加载 VMBridge 实现(org.mozilla.javascript.jdk18.VMBridge_jdk18 等),R8 一旦删除或改名
# 这些类,运行时按原始类名反射失败 -> NoClassDefFoundError + "Failed to create VMBridge instance",
# 导致签名/n 解密整条通道崩溃(issue #172/#257 的 YouTube 无法播放根因)。保留类名与成员
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.classfile.**

-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
