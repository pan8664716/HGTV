# 4.4 的主 dex 只需装下「Application 启动到 MultiDex.install 完成」这一段。
# 入口 Activity 在 install 之后才会创建，放进主 dex 会连带整棵页面依赖并撑爆 65536 方法上限。
-keep class com.hgtv.star.HgtvApplication {
    <init>();
    protected void attachBaseContext(android.content.Context);
}
-keep class androidx.multidex.** { *; }
-keep class android.support.multidex.** { *; }
