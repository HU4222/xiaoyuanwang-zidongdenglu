# 本工程未启用代码压缩（minifyEnabled false）。
# 若后续开启 R8，必须保留下列入口，否则 Xposed 模块与前台服务会被裁掉。

-keep class com.campusnet.autologin.xposed.** { *; }
-keep class com.campusnet.autologin.engine.WatchdogService { *; }
-keep class com.campusnet.autologin.auth.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
