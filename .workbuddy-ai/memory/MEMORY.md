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

## 测试设备（adb）

- `adb devices` → `192.168.31.121:5555`，S905L3B / Android 9 电视盒子，**已 root**。
- 应用 `dataDir=/data/user_de/0/com.hgtv.star`（direct-boot aware，**不是** `/data/data/`）。
  读偏好要 `adb shell su -c "grep ... /data/user_de/0/com.hgtv.star/shared_prefs/*.xml"`。
- 进度文件：`/data/user_de/0/com.hgtv.star/files/library/progress/<seriesId>.json`。
- 该机 `deviceForm` 存的是 `auto`，`autoFullscreen` 存的是 `false`。
  调试面板（`debugMode`）打开时 `uiautomator dump` 能读到播放器状态读数。
- **改偏好后必须 `am force-stop`**，否则内存里的 SharedPreferences 会把旧值写回。

## 换播放内核 / 大依赖时的检查清单

包名迁移不能只扫 `.kt`，这四处都有类名字符串：
1. `res/layout/*.xml` 里写死的自定义 View 类名（**编译期查不出，运行时才崩**）
2. `AndroidManifest.xml`
3. `baseline-prof.txt`（类不存在时系统**静默跳过**，优化直接失效）
4. `proguard-rules.pro` 的 keep 规则

## 机械改写 API 的三条红线

批量正则改写（`sed` / Python `re.sub`）时，下面三类错误**编译能过、单测能过，只有真机才暴露**：

1. **丢括号**：`f(a+b)` → `a+b.g()`。语法合法、类型正确，但运算顺序变了。
   踩过：`HttpUrl.parse(HOST+path)` → `HOST+path.toHttpUrlOrNull()`，
   `path` 是相对路径解析为 null，`HOST+null` 拼出 `…fqnovel.comnull`，扫码登录直接打空主机。
2. **替换串里的反斜杠**：`re.sub` 会把替换串里的 `\.` 当转义，结果把 `\.` 原样写进代码。
   替换串一律用裸名字拼 `.`，别写 `\.`。
3. **贪婪匹配吃掉右括号**：`\(([^()]+)\)` 不跨括号，遇到嵌套调用就会截断。
   宁可手改，也不要为「一次匹配多行」放松正则。

凡是改写后改变了「谁先算」的地方，要么保留括号，要么抽成**可单测的函数**再钉住。

## 约定

- 不主动改版本号 / versionCode，不推送（除非用户明确要求）。
- 改动后编译 debug 版本；真机测试交给用户。
