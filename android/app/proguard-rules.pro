-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# 正式包里去掉**诊断日志**（v / d / i 三档），保留 w / e 两档。
#
# 为什么要显式配这一条：`Log.i` 是同步写，而且字符串拼接在**调用之前**就已经算好了——
# 一屏几十行、一次滚动几百行，这笔开销会实打实地压在 UI 线程上。
# `ArtworkTiming` 与 `SeriesGrid` 里那些诊断日志自己用 `BuildConfig.DEBUG` 门控过
# （R8 能把那几处整段删掉），但散在各处的 `Log.i("HGTV", ...)`（首页就绪耗时、
# 播放首帧、弹幕条数、账号同步跳过…）没有门控，正式包里一直留着。
# 这里按「调用无副作用」声明，R8 会把它们连同字符串拼接一起删掉。
#
# 只删 v/d/i：`Log.w` / `Log.e` 打的是真的出问题的地方（本机记录落盘失败、
# 弹幕拉取失败…），这些在用户反馈里是唯一的线索，不能删。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ========== 友盟统计SDK混淆规则 ==========
-keep class com.umeng.** {*;}

# ========== 会话保活 ==========
# 心跳与装配入口都是 R8 看不到外部调用者的入口（`Application` 反射式调用链之外、
# 广播 action 是字符串），改名不会有功能问题，但线上排查时要能对上日志里的类名；
# 这两条同时也挡住「按字符串匹配 action / 键名」的那类裁剪。
-keep class com.hgtv.star.data.SessionBootstrap { public *; }
-keep class com.hgtv.star.data.SessionEvents { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class org.repackage.** {*;}
-keep class com.uyumao.** { *; }
-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}

# ========== 友盟APM SDK混淆规则 ==========
-keep class com.uc.** { *; }
-keep class com.efs.** { *; }
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
