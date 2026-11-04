#!/bin/zsh
# SPDX-License-Identifier: GPL-3.0-only
#
# 把已签名的 .app 打成 .ipa（分发 / 留档用）。
#
# .ipa 不是另一种构建，它就是一个 zip：
#   HGTV.ipa
#   └── Payload/
#       └── HGTV.app/…        ← 与 Xcode 产出的 .app 逐字节相同
#
# 什么时候需要它：
#   * 留档 / 备份（重装系统后还能装回来）；
#   * 用 Apple Configurator、爱思助手这类工具安装；
#   * 上 TestFlight / App Store（那种 ipa 由 Xcode 的 Archive 流程产出，带分发签名）。
#
# 什么时候不需要：
#   * Xcode 直接 Run、`xcrun devicectl device install app` 都直接吃 .app；
#   * 免费个人团队签出来的包只认本机（描述文件里只有这台设备的 UDID），
#     把 ipa 发给别人的手机是装不上的。
#
# 用法：
#   ios/tools/package_ipa.sh                 # 用 Debug-iphoneos 里已签名的产物
#   ios/tools/package_ipa.sh --unsigned      # 允许未签名产物（给 Sideloadly / AltStore 之类自己签名用）
#   ios/tools/package_ipa.sh <HGTV.app 路径>

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALLOW_UNSIGNED=0
if [[ "${1:-}" == "--unsigned" ]]; then
  ALLOW_UNSIGNED=1
  shift
fi
APP="${1:-$ROOT/build/Debug-iphoneos/HGTV.app}"
OUT="$ROOT/build"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }

if [[ ! -d "$APP" ]]; then
  red "找不到 $APP —— 先构建真机版本（ios/tools/install.sh 或 Xcode Run）。"
  exit 1
fi

# 没有签名的 .app 打出来的 ipa 直接装不上（真机要求有效签名 + 描述文件）。
# 但未签名 ipa 对 Sideloadly / AltStore 这类「自己注入证书」的工具是有用的，
# 因此用 --unsigned 显式放行，并在结尾把这一点说清楚。
if [[ "$ALLOW_UNSIGNED" -eq 0 ]]; then
  if ! codesign -dv "$APP" >/dev/null 2>&1; then
    red "这个 .app 没有签名，ipa 装不上。先在 Xcode 里 Run 一次（自动签名），再来打包；"
    red "或者加 --unsigned 打一个「需要自行签名」的包。"
    exit 2
  fi
  if [[ ! -f "$APP/embedded.mobileprovision" ]]; then
    red "缺少 embedded.mobileprovision：这不是可安装的签名产物。"
    exit 2
  fi
fi

VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Info.plist" 2>/dev/null || echo "0")
BUILD=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$APP/Info.plist" 2>/dev/null || echo "0")
SUFFIX=""
[[ "$ALLOW_UNSIGNED" -eq 1 ]] && SUFFIX="-unsigned"
NAME="HGTV_${VERSION}_${BUILD}${SUFFIX}.ipa"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Payload"
cp -R "$APP" "$WORK/Payload/"
( cd "$WORK" && zip -qry "$NAME" Payload )
mv "$WORK/$NAME" "$OUT/$NAME"

green "已生成 $OUT/$NAME"
ls -lh "$OUT/$NAME" | awk '{print "大小：" $5}'
shasum -a 256 "$OUT/$NAME" | awk '{print "SHA256：" $1}'
if [[ "$ALLOW_UNSIGNED" -eq 1 ]]; then
  printf '\033[33m%s\033[0m\n' "注意：这是未签名包，不能直接安装。"
  printf '%s\n' "  用 Sideloadly / AltStore / 爱思助手 等工具，以你的 Apple ID 现场签名后安装；"
  printf '%s\n' "  免费账号签出来的包有效期 7 天。"
fi
