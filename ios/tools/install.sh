#!/bin/zsh
# SPDX-License-Identifier: GPL-3.0-only
#
# 一键装到已连接的 iPhone：
#   1. 取设备 UDID
#   2. 从钥匙串里的 Apple Development 证书取 Team ID（登录过 Apple ID 才有）
#   3. 真机构建（自动签名，允许 Xcode 自动建证书 / 描述文件）
#   4. 安装并启动
#
# 前置条件（只需做一次）：
#   Xcode → Settings → Accounts → 用 Apple ID 登录（免费个人团队即可）
#   首次真机运行后，iPhone 上还要：设置 → 隐私与安全性 → 开发者模式（打开并重启）
#
# 用法：
#   ios/tools/install.sh              # 自动找设备与 Team
#   ios/tools/install.sh <UDID>       # 指定设备
#   TEAM=XXXXXXXXXX ios/tools/install.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$ROOT/HGTV.xcodeproj"
BUNDLE_ID="com.hgtv.star"
APP="$ROOT/build/Debug-iphoneos/HGTV.app"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
info()  { printf '→ %s\n' "$*"; }

# ---------------------------------------------------------------- 设备
UDID="${1:-}"
if [[ -z "$UDID" ]]; then
  UDID=$(xcrun devicectl list devices 2>/dev/null \
    | awk '/iPhone|iPad/ && /available|connected/ {for (i=1;i<=NF;i++) if ($i ~ /^[0-9A-F-]{36}$/) {print $i; exit}}')
fi
if [[ -z "$UDID" ]]; then
  red "没有找到已连接的 iPhone。用数据线连上、在手机上点「信任」，再重试。"
  exit 1
fi
info "设备 $UDID"

# ---------------------------------------------------------------- Team
TEAM="${TEAM:-}"
if [[ -z "$TEAM" ]]; then
  # 没有证书时 security 会返回非零，`set -e` + `pipefail` 会让脚本静默退出，
  # 因此这一整段必须兜住退出码。
  TEAM=$( (security find-certificate -a -c "Apple Development" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt sep_multiline 2>/dev/null \
    | awk -F'= *' '/OU=/{print $2; exit}') || true )
fi
if [[ -z "$TEAM" ]]; then
  red "钥匙串里没有 Apple Development 证书 —— 说明还没有登录开发者账号。"
  cat <<'TIP'

请先做这一步（只需一次）：
  1. 打开 Xcode → 菜单 Settings… → Accounts
  2. 左下角「+」→ Apple ID → 登录（免费个人团队即可，不需要 99 美元）
  3. 选中刚加的账号 → 右侧 Manage Certificates… → 左下「+」→ Apple Development
  4. 回到这里重新运行：ios/tools/install.sh

TIP
  exit 2
fi
info "Team $TEAM"

# ---------------------------------------------------------------- 构建
info "构建真机版本（首次会自动创建描述文件，可能要十几秒）"
xcodebuild -project "$PROJECT" -target HGTV -sdk iphoneos -configuration Debug \
  DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic \
  -allowProvisioningUpdates build

if [[ ! -d "$APP" ]]; then
  red "构建完成但没有找到产物：$APP"
  exit 3
fi

# ---------------------------------------------------------------- 安装
info "安装到设备"
xcrun devicectl device install app --device "$UDID" "$APP"

info "启动"
xcrun devicectl device process launch --device "$UDID" "$BUNDLE_ID" || true

green "完成。"
cat <<'TIP'

如果手机上打不开（提示「未受信任的开发者」）：
  设置 → 通用 → VPN 与设备管理 → 开发者 App → 信任你的 Apple ID
如果提示「需要开发者模式」：
  设置 → 隐私与安全性 → 开发者模式 → 打开 → 重启手机

注意：免费个人团队签的 App 有效期为 7 天，过期后重跑本脚本即可。
TIP
