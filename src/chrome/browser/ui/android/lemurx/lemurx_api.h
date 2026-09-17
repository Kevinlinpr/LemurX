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

// Lua 线程：清空 lemurx.* 持有的全部回调表（tabs.on / cdp.on / timer / ui 点击 /
// expose / http 回包）。LemurXEngine::Stop() 在 lua_close 之前调用。
void LemurXResetLuaGlobals();

// UI 线程：把浏览器进程里所有由脚本写入、且会改变 Chromium 行为的原生状态清回
// 空——net 规则、luakit webview 附着（导航否决）、证书放行表、CDP 会话、
// 自定义 scheme 表、渲染进程 web 模块清单。清完之后 ContentBrowserClient 上的
// 每个 LemurX 钩子都走「无规则 → 原样放行」分支。
void LemurXResetBrowserState();

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_API_H_
