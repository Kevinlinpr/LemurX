// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_API_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_API_H_

#include <string>

struct lua_State;

void RegisterLemurXApi(lua_State* L);
void LemurXDispatchUiClick(const std::string& overlay_id);
void LemurXDispatchUiClick(const std::string& overlay_id,
                             const std::string& json);
void LemurXDispatchTabEvent(const std::string& name, const std::string& json);
void LemurXDispatchCdpEvent(const std::string& name, const std::string& json);
void LemurXDispatchHttpResult(int request_id, const std::string& json);

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_API_H_
