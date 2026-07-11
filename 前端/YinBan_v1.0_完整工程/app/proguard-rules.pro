# ============================================================
# 路径: app/proguard-rules.pro
# 用途: AI 影伴系统 (YinBan) — ProGuard 混淆规则
# ============================================================

# 保留 Gson 序列化的数据类
-keep class com.yinban.ai.network.** { *; }
-keepclassmembers class com.yinban.ai.network.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
