#!/bin/sh
# Regenerate official_assets.gni (run after adding/removing files under
# chrome/lemurx/lua/official/). Every *.lua here ships in the apk under
# assets/lua/official/ and is seeded to files/lua/official/ by LemurXBridge.
cd "$(dirname "$0")" || exit 1
{
  echo '# Copyright 2026 The LemurX Authors'
  echo '# Use of this source code is governed by a BSD-style license that can be'
  echo '# found in the LICENSE file.'
  echo '#'
  echo '# Generated from the chrome/lemurx/lua/official/ tree: the official Lua scripts'
  echo '# (Lua re-implementations of popular Chrome extensions) plus their shared'
  echo '# framework lx/.  Regenerate:'
  echo '#   cd chrome/lemurx/lua/official && ./gen_assets_gni.sh'
  echo
  echo 'official_asset_sources = ['
  find . -type f -name '*.lua' | sed 's|^\./||' | sort | sed 's|^|  "//chrome/lemurx/lua/official/|; s|$|",|'
  echo ']'
  echo
  echo 'official_asset_destinations = ['
  find . -type f -name '*.lua' | sed 's|^\./||' | sort | sed 's|^|  "lua/official/|; s|$|",|'
  echo ']'
} > official_assets.gni
