# tools 目录说明

这个目录分成两层：根目录是工程本身要用的脚本；其余目录是接口、签名与官方 App 的本机研究材料。根目录脚本被 `README.md`、GitHub Actions 和构建脚本按原路径调用，移动前必须同步改调用方。

## 工程脚本（入库）

| 分类 | 文件 | 用途 |
| --- | --- | --- |
| 构建与发布 | `build-kotlin.sh` | 本地构建签名 APK |
| 构建与发布 | `version.sh`、`compare-version.sh`、`bump-version.sh`、`release-notes.sh` | 版本号读取、发布判定、升版、发布说明；由 Actions 和本地脚本调用 |
| 图标与界面校对 | `app-icon.py`、`theme-preview.py`、`neumorphism-preview.py`、`maintainer-avatars.py` | 生成或校对应用图标、主题图、预览图、维护者头像 |
| 静态校验 | `verify-button-system.py` | 核对按钮系统与主题令牌的静态规则 |

这些脚本都从仓库根目录按 `tools/<文件名>` 运行。校对图和临时预览写到 `docs/preview/` 或 `.local/`，不写入 `tools/`。

## 本机研究材料

| 目录 | 内容 | 入库范围 |
| --- | --- | --- |
| `login-lab/` | 本机登录试跑台、签名 Python 实现、设备/播放探针 | 基础网页工具与已入库探针；运行产物和一次性实验见下 |
| `signature-go/` | 自包含的 Go 签名模块与接口探针 | 整目录本机保留，不入库 |
| `NewFolder/` | JADX 等工具导出的官方 App 资源与反编译源码 | 整目录本机保留，不入库 |
| `官方应用参考/` | 官方 App 抓包、反编译片段、分析文档、设备库和 Frida 工具 | 整目录本机保留，不入库 |

`login-lab/` 的约定：

- 入库基础件：`README.md`、`dump_device.py`、`server.py`、`static/`、`hgclient.py`、`hgsign.py`、`hgvendor.py`。
- 入库探针：`activation_20_probe.py`、`multi_playback_probe.py`、`official_startup_replay.py`、`playback_probe.py`、`register_probe.py`、`warmup_probe.py`。
- 本机实验：`frida/`、`hgmedusa/`、`re/`、一次性 `fyworld_*`、`hongguo74332.py`、`*_medusa_probe.py`、`*_sixheader_probe.py`、`video_api_probe_74332.py`、`hgmedusa_client.py`。
- 运行产物：`.runtime/`（登录态、Cookie、抓包、日志、截图）、`__pycache__/`、`.DS_Store`。

本机资料由 `tools/.gitignore` 排除；会话、抓包、大体积二进制和反编译源码保留在原位置，不做删除，也不随项目提交。若以后要把某个实验脚本转成工程工具，先从 `.gitignore` 移除对应规则，整理入口与依赖后再入库。

## 维护原则

- 工程脚本放根目录，研究脚本放各自目录，不在根目录堆临时文件。
- 登录态、抓包、设备库、APK、so、日志和截图不入库。
- 研究目录不参与 Android/iOS 构建；改签名结论时同步核对 `android/` 与 `ios/` 的实现。
- 不把本机绝对路径、Cookie 或设备凭据写进入库脚本。
