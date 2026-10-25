# 4.4 的主 dex 必须装得下「启动到 MultiDex.install 完成」这一段：
# Application、它的父类、入口 Activity，以及 MultiDex 自己。
-keep class com.hgtv.star.HgtvApplication { *; }
-keep class com.hgtv.star.MainActivity { *; }
-keep class androidx.multidex.** { *; }
-keep class android.support.multidex.** { *; }
