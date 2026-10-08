#!/bin/bash
# LemurX: shallow checkout of a pinned official Chromium release (no history).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPOT_TOOLS="${DEPOT_TOOLS:-$ROOT/depot_tools}"
if [ ! -x "$DEPOT_TOOLS/gclient" ]; then
  git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS"
fi
export PATH="$DEPOT_TOOLS:$PATH"
export DEPOT_TOOLS_UPDATE=0
cd "$(dirname "$0")"
VER=$(sed -n 's/.*src.git@\([0-9.]*\)".*/\1/p' .gclient)
if [ ! -d src/.git ]; then
  git clone --depth 1 --branch "$VER" https://chromium.googlesource.com/chromium/src.git src
fi
gclient sync --nohooks --no-history -D --shallow -j16
gclient runhooks
echo "FETCH_DONE $VER"
