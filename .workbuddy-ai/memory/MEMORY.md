# MEMORY.md

## 界面改动红线（2026-10-29 从一次回退里学到）

**不要为了「字太小」去改 `designScale()` / 设计画布 / 页面间距。**

- 整套 UI 按 1280×720 设计画布等比缩放（`android/app/src/main/kotlin/com/hgtv/star/tv/TvMetrics.kt`）。
- 曾把手机/平板画布改成 960×540（整体放大 1.33×），用户第一反馈是「界面明显变大，
  完全不是我想要的效果」——换画布会把按钮高度、卡片尺寸、行间距、一屏行数一起放大，
  不是「只把字放大」。
- 同理，电视过扫描「默认加边距」也被撤销：它会动已经调好的版面留白。
  真要做必须做成**设置项、默认关闭**。
- 可读性优化只能动**字号**（`TextView.designTextSize` 加形态系数），不能动几何。

判断标准：用户要的是「原来的版面」，不是「更大的界面」。

## 构建

- 必须用 JDK 17：`JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home`
  （默认 java 是 26，Gradle 8.14 不支持）。仓库根已有 `.java-version` 与自检。
- Media3 被 minSdk 19 钉在 **1.4.1**（1.5.0+ 要求 minSdk 21）。

## 约定

- 不主动改版本号 / versionCode，不推送（除非用户明确要求）。
- 改动后编译 debug 版本；真机测试交给用户。
