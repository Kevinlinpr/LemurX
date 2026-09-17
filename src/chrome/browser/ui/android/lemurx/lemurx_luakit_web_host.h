// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_WEB_HOST_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_WEB_HOST_H_

#include "chrome/common/lemurx_web.mojom.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"

struct lua_State;

namespace content {
class RenderFrameHost;
}

// luakit web extension 的浏览器半边（对应 luakit 的 ipc_endpoint 集合）。
//
// 每个渲染进程一个 LuakitWebHost（渲染进程 Ready 时建立），持有反向的
// LuakitWebExtension Remote。Lua 内核（lk_ipc.lua）通过 __luakit.web_* 原语
// 发 require_web_module / ipc_channel:emit_signal / eval_js；渲染进程回来的
// 信号经 LemurXLuakitDispatch("webipc" | "weblog" | "webeval" | "webext", ...) 回投。
void RegisterLemurXLuakitWebHost(lua_State* L);

// ChromeContentBrowserClient::ExposeInterfacesToRenderer 调用（UI 线程）。
void LemurXLuakitBindWebHost(
    int render_process_id,
    mojo::PendingReceiver<lemurx::mojom::LuakitWebHost> receiver);

// webview 的 TabObserver 在主框架出现时调用：告知渲染进程该主框架属于 tab_id，
// 渲染进程据此建 page 对象并发 page-created。
void LemurXLuakitWebNotifyPage(content::RenderFrameHost* main_frame, int tab_id);

// UI 线程。用户关掉 Lua 时调用：清空 web 模块清单与待送达的主框架通知。
void LemurXLuakitWebHostResetAll();

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_WEB_HOST_H_
