#!/bin/bash
# LemurX: shallow checkout of a pinned official Chromium release (no history).
set -euo pipefail
export PATH="$HOME/code/lemurx/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
cd "$(dirname "$0")"
VER=$(sed -n 's/.*src.git@\([0-9.]*\)".*/\1/p' .gclient)
if [ ! -d src/.git ]; then
  git clone --depth 1 --branch "$VER" https://chromium.googlesource.com/chromium/src.git src
fi
gclient sync --nohooks --no-history -D --shallow -j16
gclient runhooks
echo "FETCH_DONE $VER"
