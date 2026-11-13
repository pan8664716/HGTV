# HGTV iOS（iPhone）

与 Android `feature-phone` 功能对等的 iPhone 应用：SwiftUI + AVPlayer，iOS 17+。

## 快速开始

```sh
cd ios
python3 tools/gen_project.py        # 生成 HGTV.xcodeproj（新增/删除 .swift 后必须重跑）
open HGTV.xcodeproj                 # 选一个 iPhone 模拟器或真机运行
```

命令行编译（不需要签名）：

```sh
xcodebuild -project HGTV.xcodeproj -target HGTV -sdk iphonesimulator \
           -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

真机运行需要在 target 里填 `DEVELOPMENT_TEAM`。

## 目录

```
HGTV/
├── App/        入口、路由、根视图、全局提示
├── Design/     主题（20 套）、画布、柔光、图标、控件、海报卡、视频层
├── Core/       模型、规则、签名、接口、播放地址解密、CENC 解密
├── Data/       本机书库（收藏/历史/进度/偏好）、会话、离线视频
└── Features/   推荐 / 分类 / 榜单 / 我的 / 详情 / 播放 / 搜索 / 设置 / 账号
```

## 验证脚本

```sh
# 1) 签名：12 组固定向量逐字节比对
swiftc -O HGTV/Core/Sign/*.swift tools/signer_oracle.swift -o /tmp/signer_oracle
/tmp/signer_oracle ../android/core/src/test/resources/signing.json

# 2) 接口链路：注册 → 榜单 → 分类 → 详情 → 播放地址 → 搜索
swiftc -O HGTV/Core/Sign/*.swift HGTV/Core/API/*.swift HGTV/Core/MediaCrypto.swift \
       HGTV/Core/Mp4Crypto.swift HGTV/Data/OfflineVideo.swift HGTV/Core/Models.swift \
       HGTV/Core/Rules.swift HGTV/Data/Session.swift tools/api_smoke.swift -o /tmp/api_smoke
/tmp/api_smoke

# 3) 解密：取一集 → 改包解密 → AVFoundation 读轨道与时长
swiftc -O HGTV/Core/Sign/*.swift HGTV/Core/API/*.swift HGTV/Core/MediaCrypto.swift \
       HGTV/Core/Mp4Crypto.swift HGTV/Data/OfflineVideo.swift HGTV/Core/Models.swift \
       HGTV/Core/Rules.swift HGTV/Data/Session.swift tools/decrypt_check.swift -o /tmp/decrypt_check
/tmp/decrypt_check
```

三个脚本都不进 App，只依赖 Foundation / CommonCrypto / AVFoundation，可以直接在 macOS 上跑。

## 设计约定

- 版面按 360×640 设计画布等比缩放（`Design/Theme.swift` 的 `PhoneMetrics`），从 SE 到 Pro Max 比例不变。
- 层次只用「凸起 / 凹陷」与柔光表达，不描边、不用色块分区；品牌色只做线与小色块。
- 规则（热度、集数、季号、分页、去重、降级）全部在 `Core/Rules.swift`，页面不重复实现。
- 内容接口、扫码登录、设备注册和官网搜索优先使用 `Core/API/TncConfigStore.swift`
  的 TNC 下发地址；TNC 未命中对应角色时才回退静态域名。
- App 搜索结果每页请求 50 条；官网搜索保持服务端固定 10 条一页，由客户端按页拼接。

更完整的页面映射、数据契约与已知边界见 [`../docs/IOS_PORT.md`](../docs/IOS_PORT.md)。
