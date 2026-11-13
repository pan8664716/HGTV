<p align="center"><img src="docs/preview/app-logo.png" width="192" alt="HGTV 应用图标"></p>

# HGTV（Android TV / 手机 / 平板，4.4+）

短剧客户端：单 APK、Kotlin + ExoPlayer，电视用遥控器、手机平板用触屏，装好联网即可看；不登录也能浏览播放，登录可同步云端收藏、历史与预约，同一局域网还可迁移完整本机数据。

## 目录

- [功能](#功能)
- [界面](#界面)
- [下载](#下载)
- [当前限制](#当前限制)
- [工程结构](#工程结构)
- [开发](#开发)
- [维护者](#维护者)
- [参与贡献](#参与贡献)
- [交流](#交流)

## 功能

- **浏览**：首页分区剧架、榜单（多榜单）、分类页（大类 / 小类）；分类面板与已选列表会缓存，切换大类或筛选时不再重复加载；海报卡左下角带热度角标（没有热度就不显示），列表右下角有「回到顶部」（可设为始终显示 / 下拉几行后显示 / 不显示，首页不显示）。
- **推荐**：打开应用可直接进推荐页，上下换剧、左右快进、按确定看正片；进度条与正式播放一致，可拖动并显示缓存进度；推荐范围与男频 / 女频可在设置调整，隐藏过的剧集可一键恢复。
- **搜索**：电视默认 HG 键盘；历史搜索在前、推荐词在后，每页 50 条；搜索源可切 App / 官网，也可扫码用手机输入。
- **收藏与记录**：本机收藏、最近观看、稍后看，支持断点续播与「继续观看」直达；历史可按已看完 / 未看完筛选、可一键清空；手机与平板的「我的」页栏目可横向滑动，并支持当页批量勾选删除；卡片长按或按菜单键可收藏 / 稍后看 / 隐藏推荐 / 删除记录。
- **登录**：「我的」页扫码或手机号 + 验证码两种登录，二维码过期自动刷新，登录后可退出或刷新；进入我的页先看第三方客户端使用须知，取验证码前每次确认。
- **设备同步**：同一局域网内可选择「同步其他设备」或「作为同步源」，自行勾选登录信息、设备参数、播放收藏记录和设置信息；记录合并并保留进度更高的一条。
- **重装恢复**：卸载重装后自动恢复登录与设备身份，不用重新注册、重新收验证码。
- **预约**：显示上线时间，可按已上线 / 待上线筛选；详情页列出相关剧集。
- **选集**：详情页与播放器内同一套选集网格，每页 10–50 集可选、按页翻看；边缘翻页可关，关掉后左右边缘改为选上一集 / 下一集。
- **播放**：360P / 480P / 540P / 720P / 1080P 上限、0.75–2× 倍速（手机平板多一档 3×）、三种画面模式、画面缩放 1–100%、自动连播，最后一集有下一季时自动进入下一季；播放域名由 TNC 动态选择，取流失败会切换候选域名与备用地址并自动重试；手机与平板播放页提供相关剧集、作者作品与加入收藏，并兼容 Android 4.4–9；推荐与播放页会批量预取后续剧集，快速跳集更稳；起播复用本机缓存，再次打开更快；左右滑动调进度，进度条可拖动并显示缓存进度，画面上可常显剧名 / 剧集 / 网速 / 时间；播放设置、选集面板与底部控制条可设 2–15 秒无操作自动收起（暂停、拖进度同样计时），手机平板的推荐页也一样；正式播放的上下换集与推荐页左右翻页使用统一的跟手吸附动画。
- **视频解码**：硬件解码（默认，省电、4K 更稳）与软件解码（兼容性好，花屏 / 绿屏 / 音画不同步时用）可切换。
- **按键自定义**：播放界面的上下左右 / 菜单 / 确定键各做什么可改，支持一键恢复默认。
- **弹幕**：切集和进入下一季时保留上一集弹幕并无缝衔接；默认上方显示，也可切换只显一行 / 全屏 / 隐藏，表情标签还原成表情图；速度、字号与透明度可调；相同弹幕可聚合（2–10 条起聚，持续出现累计计数，停发 20 秒后重计），聚合弹幕展示 7 秒、每次 +1 重新计时，按显示方式最多同屏 1 / 2 / 3 条；可开启或关闭聚合特效，特效包含计数放大、渐热颜色与高计数冲击效果。
- **定时停止**：按分钟或按集数到点暂停并保存进度。
- **播完推荐**：一集播完自动接下一集；最后一集有下一季时自动进入下一季，否则推荐下一部。
- **手机辅助**：扫码在手机上输入搜索词、备份与恢复本机记录；局域网全量同步可在手机、平板和电视之间迁移登录、设备、记录与设置。
- **界面**：启动时显示品牌 Logo 与加载状态，初始化失败可直接重试；20 套材质主题、手机竖屏每行 1–3 张 / 横屏 4–6 张、电视每行 4–8 张、功能显示开关（导航入口 / 首页栏目 / 播放信息各自可关）、减少动画优化性能、设备类型（手机 / 平板 / 电视）、竖屏短剧全屏（仅手机、平板可用）；手机与平板可点按操作，横屏主页、详情、登录、设置与选集完整适配，平板点画面出控制条，界面字号与每行卡片数可调（首次进入会提示）；手机双击返回退出，电视按两次返回退出。
- **设置**：四大项分页（播放偏好 / 内容与来源 / 按键与弹幕 / 系统高级），偏好自动保存；手机和电视均可切换设备类型；播放设置与系统设置提供调试模式开关；选集分页、边缘翻页、播放界面自动消失秒数、弹幕聚合条数、返回顶部显示时机均可调；可查看开源许可、清空应用缓存（恢复出厂）。

## 界面

新拟态（Neumorphism）风格：全站只有一块材质，层次由「凸起 / 凹陷」与柔光表达；
播放界面不套新拟态，压在画面上的字与线固定暖白，避免和画面抢注意力。20 套材质主题（十套深色、十套浅色，默认深海蓝）如下：

交互原型（1280×720，含 20 套主题与各页面焦点态）：[`docs/prototype/index.html`](docs/prototype/index.html)。

<table>
<tr>
<td align="center"><img src="docs/preview/1.jpg" width="100%" alt="界面截图 1"></td>
<td align="center"><img src="docs/preview/2.jpg" width="100%" alt="界面截图 2"></td>
<td align="center"><img src="docs/preview/3.jpg" width="100%" alt="界面截图 3"></td>
</tr>
<tr>
<td align="center"><img src="docs/preview/4.webp" width="100%" alt="界面截图 4"></td>
<td align="center"><img src="docs/preview/5.webp" width="100%" alt="界面截图 5"></td>
<td align="center"><img src="docs/preview/6.webp" width="100%" alt="界面截图 6"></td>
</tr>
<tr>
<td align="center"><img src="docs/preview/7.jpg" width="100%" alt="界面截图 7"></td>
<td align="center"><img src="docs/preview/8.jpg" width="100%" alt="界面截图 8"></td>
<td align="center"><img src="docs/preview/9.jpg" width="100%" alt="界面截图 9"></td>
</tr>
</table>

## 下载

从 [Releases](https://github.com/pan8664716/HGTV/releases/latest) 下载最新正式 APK，拷到设备安装，或用 ADB 覆盖安装：

```sh
adb install -r HGTV_1.3.7.apk    # 文件名：<标识>_<版本号>.apk
```

应用内不提供更新入口，新版一律手动下载覆盖安装（本机收藏、进度与设置保留）。
当前版本 `1.3.7`（versionCode 76），各版本改动见[变更记录](docs/CHANGELOG.md)。
连不上 GitHub 时可用[镜像地址](https://gh-proxy.org/https://github.com/pan8664716/HGTV/releases/latest)。
GitHub Release 只提供 APK、校验文件和更新元数据，不附带项目源码。

应用与内容平台官方无关联，内容接口为非官方实现，平台变化可能导致失效；
当前已知的限制见 [当前限制](#当前限制)。

### 依赖

- Android TV、手机或平板，Android 4.4（API 19）及以上；设备形态可在设置里手动指定。
- APK 集成友盟统计与 APM，原生库仅保留 ARM 32/64 位；视频解码能力取决于设备，
  遇到花屏、绿屏或音画不同步可在设置里改用软件解码。
- 换用别的签名重建后不能覆盖安装本仓库发布密钥签名的 APK（卸载会清除本机记录）。

## 当前限制

平台是非官方接口，随版本变化随时可能失效。当前已知的限制：

### 播放

- 拉流地址是 CDN 直链，**有时效**；清晰度上限 1080P，个别编码的流会被跳过
  （部分设备只有声音没画面）。
- 偶发拿不到地址时会先用备用设备重试，仍失败再重新注册设备；仍然失败时界面给出可重试的说明，稍后再试即可。

### 其他

| 现象 | 说明 |
| --- | --- |
| 搜索没结果 | 接口偶发空响应，会自动重发；仍为空时下次启动会重新注册设备 |
| 弹幕条数少于平台自报 | 平台只下发采样热弹幕 |
| 找不到更新入口 | 应用内不提供更新，新版需手动下载覆盖安装 |
| 电视上「竖屏短剧全屏」是灰的 | 这一项只在手机 / 平板上可用；电视的画面本来就按等比铺满摆放，不需要再旋转 |

平台接口随版本变化，个别功能可能短期不可用；界面上会给出可重试的说明。

## 工程结构

```
android/                Gradle 工程根（Android Studio 直接打开这一层）
├─ app/                 Application、启动路由、Manifest、图标与签名
├─ core/                网络协议、签名、解析、播放状态机（纯 JVM，可在 JVM 上跑单测）
│  └─ src/test/         单元测试与固定向量
├─ shared/              账号 / 会话 / 收藏 / 进度 / 封面 / 播放传输 / 弹幕渲染 / 调试面板
├─ feature-tv/          电视界面：遥控器 + 焦点导航 + 1280×720 设计画布
├─ feature-phone/       手机平板界面：触摸 + 竖屏 + 手势播放
├─ gradle/ gradlew      Gradle 8.14.3 包装器
└─ build.gradle.kts     构建脚本与发布签名配置
docs/                   项目文档；preview/ 放应用图标与界面截图
tools/                  工程脚本；目录清单与本机资料约定见 tools/README.md
README.md
```

依赖方向单向，不允许反向或横向依赖：`:core` ← `:shared` ← `:feature-tv` / `:feature-phone` ← `:app`。

## 开发

需要 JDK 17、Android SDK Platform 36（Build Tools 35.0.0 及以上）；Gradle 8.14.3 包装器已随仓库提供。
设置 `ANDROID_HOME`，或在 `android/local.properties` 写入 `sdk.dir=<SDK 绝对路径>`。

```sh
# 开发包：直接可安装，无需发布私钥
./android/gradlew -p android :core:test :app:lintDebug :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

静态校验与离线校对图（校对图输出到 `docs/preview/.local/`，不入库）：

```sh
python3 tools/verify-button-system.py    # 按钮系统规则（全站唯一实现、三档尺寸、令牌化内边距）
python3 tools/app-icon.py --check        # 矢量 / 位图 / 背景色与脚本是否同源
python3 tools/app-icon.py                # 重出应用图标、电视横幅与 docs/preview/app-logo.png
python3 tools/theme-preview.py           # 20 套主题对比度自检 → docs/preview/theme.png
python3 tools/neumorphism-preview.py     # 首页 / 按钮六态 / 强调色校对（仅本机查看）
python3 tools/maintainer-avatars.py --check  # 维护者头像是否为 256px 圆形透明底
tools/version.sh                            # 应用名 / 标识 / 版本号 / 版本 code / 产物名
```

## iOS 端（iPhone）

`ios/` 是与手机端功能对等的 iOS 应用（SwiftUI，iOS 17+）：推荐流、分类、榜单、详情、播放、
搜索、我的、设置、扫码登录全部落地，接口签名与解密链路和 Android 同一套。

```sh
cd ios
python3 tools/gen_project.py     # 新增 .swift 后重跑，生成 HGTV.xcodeproj
xcodebuild -project HGTV.xcodeproj -target HGTV -sdk iphonesimulator \
           -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

移植细节、页面映射、数据契约与已知边界见 [`docs/IOS_PORT.md`](docs/IOS_PORT.md)。

## 维护者
<table>
<tr>
<td align="center"><a href="https://github.com/pan8664716"><img src="docs/maintainers/pan8664716.png" width="88" alt="pan8664716"><br><sub><b>pan8664716</b></sub></a></td>
</tr>
</table>

## 参与贡献
提问或报 bug 请开 [Issue](https://github.com/pan8664716/HGTV/issues)；提交代码前请先开 Issue 说明意图。

## 交流

文档里没写到的那些事，仓库主页侧栏留了个去处；能复现的问题仍建议开 Issue，方便留档与检索。
