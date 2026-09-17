// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_WEBVIEW_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_WEBVIEW_H_

#include <memory>
#include <string>

struct lua_State;

namespace content {
class NavigationHandle;
class NavigationThrottle;
class WebContents;
}  // namespace content

// luakit widget{type="webview"} 的原生半边。
//
// Lua 侧（kernel/lk_webview.lua）把一个 Tab 包成 webview 对象；这里提供：
//  * 每个已附着 Tab 一个 WebContentsObserver，把 load-status / property::* /
//    favicon / crashed / audio 等事件回投到 Lua（LemurXLuakitDispatch("webview", tab_id, json)）
//  * NavigationThrottle：navigation-request / new-window-decision 信号的否决能力
//    （DEFER → 问 Lua → Resume / Cancel）
//  * Tab 级操作：load_string、find、history、session_state、certificate、crash …
//
// 全部注册进隐藏全局表 __luakit（wv_* 前缀）。
void RegisterLemurXLuakitWebview(lua_State* L);

// ChromeContentBrowserClient::CreateThrottlesForNavigation 调用：
// 该 WebContents 若被 Lua 附着则返回节流器，否则 nullptr。
std::unique_ptr<content::NavigationThrottle>
LemurXLuakitMaybeCreateNavigationThrottle(content::NavigationHandle* handle);

// ChromeContentBrowserClient::AllowCertificateError 调用：
// luakit.allow_certificate(host, cert) 放行过的主机返回 true。
bool LemurXLuakitIsCertificateAllowed(const std::string& host);

// 该 WebContents 被 Lua 包成 webview 时返回 tab id，否则 -1。UI 线程。
int LemurXLuakitTabIdForWebContents(content::WebContents* web_contents);

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_WEBVIEW_H_
