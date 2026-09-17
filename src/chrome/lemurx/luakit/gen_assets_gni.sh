#!/bin/sh
# Regenerate luakit_assets.gni (run after adding/removing files under
# kernel/, lib/ or config/).
cd "$(dirname "$0")" || exit 1
{
  echo '# Copyright 2026 The LemurX Authors'
  echo '# Use of this source code is governed by a BSD-style license that can be'
  echo '# found in the LICENSE file.'
  echo '#'
  echo '# Generated from the chrome/lemurx/luakit/ tree: assets of the luakit-compatible'
  echo '# runtime shipped inside the apk.  Regenerate:'
  echo '#   cd chrome/lemurx/luakit && ./gen_assets_gni.sh'
  echo
  echo 'luakit_asset_sources = ['
  find kernel lib config -type f -name '*.lua' | sort | sed 's|^|  "//chrome/lemurx/luakit/|; s|$|",|'
  echo ']'
  echo
  echo 'luakit_asset_destinations = ['
  find kernel lib config -type f -name '*.lua' | sort | sed 's|^|  "luakit/|; s|$|",|'
  echo ']'
} > luakit_assets.gni
