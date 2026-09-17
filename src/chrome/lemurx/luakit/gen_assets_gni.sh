#!/bin/sh
# 重新生成 luakit_assets.gni（新增/删除 lib 文件后跑一次）
cd "$(dirname "$0")" || exit 1
{
  echo '# Copyright 2026 The LemurX Authors'
  echo '# 由 chrome/lemurx/luakit/ 目录树生成：luakit 兼容运行时随 apk 打包的资产。'
  echo '# 重新生成：cd chrome/lemurx/luakit && ./gen_assets_gni.sh'
  echo
  echo 'luakit_asset_sources = ['
  find kernel lib config resources -type f | sort | sed 's|^|  "//chrome/lemurx/luakit/|; s|$|",|'
  echo '  "//chrome/lemurx/luakit/COPYING.GPLv3",'
  echo '  "//chrome/lemurx/luakit/AUTHORS",'
  echo ']'
  echo
  echo 'luakit_asset_destinations = ['
  find kernel lib config resources -type f | sort | sed 's|^|  "luakit/|; s|$|",|'
  echo '  "luakit/COPYING.GPLv3",'
  echo '  "luakit/AUTHORS",'
  echo ']'
} > luakit_assets.gni
