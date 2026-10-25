#!/usr/bin/env bash
# 生成发布说明：**直接从 docs/CHANGELOG.md 抓当前版本那一节**，不另写一套模板。
#
# 用法：
#   tools/release-notes.sh notes    # 客户端更新弹窗用的纯文字短说明（长度封顶）
#   tools/release-notes.sh body     # GitHub Release 正文（版本条目 + 安装与校验）
#
# 为什么要从 CHANGELOG 抓：发布说明原先是在 workflow 里写死的四行模板，
# 每个版本一模一样；改成从 git 提交推也还是「两处维护」。现在唯一的事实来源是
# docs/CHANGELOG.md 里 `## <版本号>：<标题>` 这一节，发版时直接取出来用，
# 改了 CHANGELOG 就等于改了发布说明，不会再对不上。
#
# 环境变量：
#   HGTV_VERSION  覆盖版本号（默认 tools/version.sh version）
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

mode="${1:-body}"
version="${HGTV_VERSION:-$(tools/version.sh version)}"
code="$(tools/version.sh code)"
min_sdk="$(tools/version.sh min-sdk)"
# 「最低系统版本」这句话里的数字从 build.gradle.kts 读，避免两边各写一份之后对不上。
case "$min_sdk" in
  19) min_android="4.4" ;;
  21) min_android="5.0" ;;
  23) min_android="6.0" ;;
  26) min_android="8.0" ;;
  28) min_android="9" ;;
  *)  min_android="API $min_sdk" ;;
esac
changelog="docs/CHANGELOG.md"
[[ -f "$changelog" ]] || { echo "找不到 $changelog" >&2; exit 1; }

# 抓 `## <version>：...` 起、到下一个 `## ` 之前的内容（版本号按字面匹配，
# 用 ^## 锚定行首，避免把 `1.0.0` 匹配到 `1.0.0-rc1` 之类）。
section="$(awk -v want="$version" '
  /^## / {
    if (found) exit
    line = $0
    sub(/^## /, "", line)
    sub(/[：:].*$/, "", line)
    gsub(/^[ \t]+|[ \t]+$/, "", line)
    if (line == want) { found = 1; next }
  }
  found { print }
' "$changelog")"

if [[ -z "${section//[[:space:]]/}" ]]; then
  echo "::error::$changelog 里没有 $version 这一节（## $version：...）" >&2
  exit 1
fi

# 去掉节末可能残留的分隔线（---）与多余空行：节内容到下一个 ## 之前，
# 中间会夹着上一节收尾的 `---`，不属于这一版的内容。
section="$(printf '%s\n' "$section" \
  | sed -e 's/[[:space:]]*$//' \
  | sed -e '/^---[[:space:]]*$/d' \
  | awk '{ lines[NR] = $0 } END {
      last = NR
      while (last > 0 && lines[last] == "") last--
      first = 1
      while (first <= last && lines[first] == "") first++
      blank = 0
      for (i = first; i <= last; i++) {
        if (lines[i] == "") { blank++; if (blank > 1) continue } else blank = 0
        print lines[i]
      }
    }')"

if [[ "$mode" == notes ]]; then
  # 客户端弹窗只显示一段纯文字：剥掉 Markdown 标记，去掉空行，长度封顶。
  # AppUpdate.MAX_NOTES = 1500，这里留出余量（末尾还要加一行指向发布页）。
  notes="$(printf '%s\n' "$section" \
    | sed -E 's/^#{3,6}[[:space:]]*(.+)$/\1：/; s/^- /· /; s/^\* /· /' \
    | sed -E 's/\*\*([^*]+)\*\*/\1/g' \
    | sed '/^[[:space:]]*$/d')"
  if (( ${#notes} > 1100 )); then
    notes="$(printf '%s' "$notes" | cut -c1-1100)"
    notes+=$'\n…完整说明见发布页'
  fi
  printf '%s\n' "$notes"
  exit 0
fi

{
  printf '红果TV %s（versionCode %s）\n\n' "$version" "$code"
  printf '%s\n' "$section"
  printf '\n---\n\n'
  printf '安装包：`HGTV_%s.apk`\n' "$version"
  printf '校验：`SHA256SUMS`（`sha256sum -c SHA256SUMS`）\n\n'
  printf '最低 Android %s（API %s），沿用同一发布签名，可覆盖安装并保留本机记录。\n' "$min_android" "$min_sdk"
}
