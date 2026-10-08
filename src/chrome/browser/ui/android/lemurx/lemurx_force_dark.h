// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_FORCE_DARK_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_FORCE_DARK_H_

#include <optional>
#include <string>
#include <vector>

namespace content {
class WebContents;
}

// lemurx.chrome.setForceDark(): Lua takes over Blink's force-dark decision
// (the engine behind Chrome Android's "auto dark theme for web contents").
// Chrome only enables it when the app UI is in night mode; LemurX lets a
// script turn it on/off independently and keep per-site exclusion lists.
//
// State is read on the UI thread from
// ChromeContentBrowserClient::OverrideWebPreferences* and written from the
// Lua thread; a lock guards it.

struct LemurXForceDarkState {
  // nullopt = not managed by Lua (Chrome's own logic applies).
  std::optional<bool> enabled;
  // Hosts (exact or any subdomain) where the decision is inverted:
  //   enabled == true  -> these hosts stay light
  //   enabled == false -> these hosts are darkened
  std::vector<std::string> exceptions;
};

void LemurXSetForceDark(LemurXForceDarkState state);
LemurXForceDarkState LemurXGetForceDark();

// Called by ChromeContentBrowserClient on the UI thread. nullopt when Lua is
// not managing force dark.
std::optional<bool> LemurXForceDarkOverride(content::WebContents* web_contents);

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_FORCE_DARK_H_
