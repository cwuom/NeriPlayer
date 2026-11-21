# Deliberately conservative smoke rules; this APK is not a release-size benchmark.
-keep class app.cash.zipline.QuickJs { *; }
-keep class app.cash.zipline.QuickJs$Companion { *; }
-keep class org.mozilla.javascript.** { *; }
