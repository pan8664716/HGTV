# Android v5 Medusa 签名

Android 的内容、账号、目录、搜索和播放请求统一使用 Go 参考实现的 v5 Medusa 状态机。
`video_model` 与其他请求共用同一套算法、设备档案和请求计数，没有独立 profile。

## 请求约定

- 六头固定为 `x-khronos / x-ladon / x-argus / x-gorgon / x-helios / x-medusa`。
- `_rticket` 逐个递增，直到 query 与 body 的原始摘要分支命中 12。
- query 不额外添加 `ts`；`x-khronos` 仍为当前秒。
- JSON body 先 gzip，`x-ss-stub` 取压缩后正文的 MD5，签名也绑定压缩后正文。
- `x-ladon` 为 4 字节随机值，`x-argus` 为 `base64(le32(ts))`，`x-gorgon` 前缀对齐 `00 81`。
- `x-medusa` 使用 v5 magic、license、sign key、sbox、完整设备字段和递增请求计数。

## video_model 参数

播放模型 query 按 Go 抓包顺序生成，`_rticket` 保留在固定位置。单集
`video_model` 使用 `content_type=1004`、`device_level=4`、`source=1`、
`video_platform=3` 和 `use_server_dns=false`。

## 验证

本轮只修改 Android，构建结果记录在 `android/REFACTORING.md`。真机请求结果由用户验收。
