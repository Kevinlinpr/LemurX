#!/usr/bin/env bash
# release-apk.sh — lemurx 发版一键：把 APK 传到 Cloudflare R2，并更新 prod 的 release.json。
#
# 用法：
#   release-apk.sh <apk-path> [target-name] [notes]
#     apk-path    本地 APK 文件（必填）
#     target-name 发布文件名，如 aero-15.apk；不填则自动取下一个（R2 里最大的 aero-N + 1，
#                 R2 为空时看 prod /var/lib/aero-releases/；两边都列不出来就报错，不猜）
#     notes       更新说明；不填则用默认文案
#
# 依赖：rclone（配好一个名为 r2 的 remote，指向 Cloudflare R2）、aapt、ssh、curl。
# 环境变量（都有默认值）：
#   R2_BUCKET     R2 桶名（默认 aero-releases）
#   R2_DOMAIN     下载域名（默认 dl.aero.osmangroupx.com）
#   PROD_SSH_KEY  prod SSH 密钥（默认 lemurx/src/生产和密钥/certs/aero-master.pem）
#   PROD_SSH      prod SSH 目标（默认 ubuntu@18.143.226.219，端口 222）
#   PROD_RELEASES prod 上 release.json 所在目录
#                 （默认 /home/ubuntu/docker-compose/aero/data/openmuse/releases）
#
# 流程：读 APK 版本（aapt）→ 定文件名（拒绝覆盖 R2 里同名不同大小的文件）
#       → 传 R2（长缓存，文件名带版本永不覆盖）→ 验 Cloudflare HEAD
#       → 备份并更新 prod release.json → 验 /api/app/release。
# 注意：release.json 的 force=true、channel=test 沿用之前发布的设置；
#       网站（osman-group-limited）的链接不在这个脚本里改，需要另外更新。
# 注意：脚本里不放任何密钥。R2 凭据只存在盒子的 rclone 配置里，prod SSH 只走 .pem 文件。
set -euo pipefail

APK="${1:?用法：release-apk.sh <apk-path> [target-name] [notes]}"
TARGET_ARG="${2:-}"
NOTES_ARG="${3:-}"
R2_BUCKET="${R2_BUCKET:-aero-releases}"
R2_DOMAIN="${R2_DOMAIN:-dl.aero.osmangroupx.com}"
PROD_SSH_KEY="${PROD_SSH_KEY:-/home/user/code/lemurx/src/生产和密钥/certs/aero-master.pem}"
PROD_SSH="${PROD_SSH:-ubuntu@18.143.226.219}"
PROD_RELEASES="${PROD_RELEASES:-/home/ubuntu/docker-compose/aero/data/openmuse/releases}"
SSH_OPTS=(-i "$PROD_SSH_KEY" -p 222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20)

[ -f "$APK" ] || { echo "找不到 APK：$APK" >&2; exit 1; }
command -v rclone >/dev/null || { echo "没找到 rclone，先配好名为 r2 的 remote" >&2; exit 1; }

AAPT="$(ls /home/user/android-sdk/build-tools/*/aapt 2>/dev/null | head -1)"
[ -n "$AAPT" ] || { echo "没找到 aapt" >&2; exit 1; }
PKG_LINE="$("$AAPT" dump badging "$APK" 2>/dev/null | grep -E '^package:' | head -1)"
VERSION_CODE="$(echo "$PKG_LINE" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
VERSION_NAME="$(echo "$PKG_LINE" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "读不到 APK 版本" >&2; exit 1; }
APK_SIZE="$(stat -c %s "$APK")"
echo "APK：$APK（versionCode=$VERSION_CODE，versionName=$VERSION_NAME，$APK_SIZE 字节）"

max_n_from_list() {
  echo "$1" | grep -oE 'aero-[0-9]+\.apk' | grep -oE '[0-9]+' | sort -n | tail -1 || true
}

if [ -n "$TARGET_ARG" ]; then
  TARGET="$TARGET_ARG"
else
  MAX_N=""
  if R2_LIST="$(rclone ls "r2:$R2_BUCKET" 2>/dev/null)"; then
    MAX_N="$(max_n_from_list "$R2_LIST")"
  fi
  if [ -z "$MAX_N" ]; then
    if PROD_LIST="$(ssh "${SSH_OPTS[@]}" "$PROD_SSH" 'ls /var/lib/aero-releases/ 2>/dev/null' 2>/dev/null)"; then
      MAX_N="$(max_n_from_list "$PROD_LIST")"
    fi
  fi
  if [ -z "$MAX_N" ]; then
    echo "无法确定下一个文件名（R2 和 prod 都列不出来），请显式传 target-name" >&2
    exit 1
  fi
  TARGET="aero-$(( MAX_N + 1 )).apk"
fi
echo "发布文件名：$TARGET"

NOTES="${NOTES_ARG:-版本号 $VERSION_NAME。}"
NOTES="${NOTES//\'/}"
URL="https://$R2_DOMAIN/$TARGET"

EXISTING_SIZE="$(rclone ls "r2:$R2_BUCKET/$TARGET" 2>/dev/null | awk '{print $1}' || true)"
if [ -n "$EXISTING_SIZE" ]; then
  if [ "$EXISTING_SIZE" = "$APK_SIZE" ]; then
    echo "R2 里已有同名同大小的文件，跳过上传"
  else
    echo "R2 里已有同名但大小不同的文件，拒绝覆盖：$TARGET" >&2
    exit 1
  fi
else
  echo "上传到 R2：r2:$R2_BUCKET/$TARGET"
  rclone copyto "$APK" "r2:$R2_BUCKET/$TARGET" --header "Cache-Control: public, max-age=31536000, immutable"
fi

echo "验证 Cloudflare 下载：$URL"
CLEN="$(curl -sI -m 60 "$URL" | grep -i '^content-length:' | tr -d '\r' | awk '{print $2}')"
[ "$CLEN" = "$APK_SIZE" ] || { echo "Cloudflare 文件长度不对：$CLEN（应为 $APK_SIZE）" >&2; exit 1; }
echo "Cloudflare 已就绪（200，长度一致）"

echo "更新 prod release.json"
ssh "${SSH_OPTS[@]}" "$PROD_SSH" "cd '$PROD_RELEASES' && cp release.json release.json.bak && printf '%s' '{\"versionCode\":$VERSION_CODE,\"versionName\":\"$VERSION_NAME\",\"url\":\"$URL\",\"notes\":\"$NOTES\",\"force\":true,\"channel\":\"test\"}' > release.json && cat release.json"

echo "验证 /api/app/release"
API_JSON="$(curl -s -m 20 https://api.aero.osmangroupx.com/api/app/release)"
echo "$API_JSON"
echo "$API_JSON" | grep -q "\"versionCode\":$VERSION_CODE" || { echo "API 返回的 versionCode 不符，release.json 备份在 prod 的 release.json.bak" >&2; exit 1; }
echo "完成：$TARGET（vc $VERSION_CODE / $VERSION_NAME）已发布到 $URL"
