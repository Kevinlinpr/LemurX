// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_SCHEME_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_SCHEME_H_

#include <optional>
#include <string>
#include <string_view>

#include "content/public/browser/content_browser_client.h"
#include "content/public/browser/frame_tree_node_id.h"
#include "mojo/public/cpp/bindings/pending_remote.h"
#include "services/network/public/mojom/url_loader_factory.mojom.h"

struct lua_State;
class GURL;

namespace url {
class Origin;
}

// luakit.register_scheme(name) 的原生半边。
//
// 一个 Lua 注册过的 scheme（luakit:// gopher:// adblock-blocked:// …）在这里
// 变成 Chromium 的“非网络 scheme”：
//  * IsHandledURL 返回 true，导航不会被当成外部协议扔给 Intent
//  * 注册成 web-safe scheme，渲染进程发起的 <a href="luakit://…"> 能过安全检查
//  * 导航主资源与子资源都走 SchemeURLLoaderFactory；每个请求投给 Lua
//    （LemurXLuakitDispatch("scheme", request_id, json{uri,scheme,tab,method})），
//    Lua 侧在对应 webview 上发 "scheme-request::<name>"(uri, request)，
//    request:finish(data, mime) 再回到这里 → __luakit.scheme_reply(id, data, mime)
//
// 全部注册进隐藏全局表 __luakit（scheme_* 前缀）。
void RegisterLemurXLuakitScheme(lua_State* L);

// ChromeContentBrowserClient::IsHandledURL 调用。
// 接 string_view：GURL::scheme() 在 154 返回 string_view。
bool LemurXLuakitIsSchemeRegistered(std::string_view scheme);

// UI 线程。用户关掉 Lua 时调用：让所有等 Lua 回内容的请求失败，并清空注册表，
// 之后 IsHandledURL / 工厂入口全部走「未注册」分支。
void LemurXLuakitSchemeResetAll();

// ChromeContentBrowserClient::CreateNonNetworkNavigationURLLoaderFactory 调用：
// scheme 被 Lua 注册过则返回工厂，否则返回空 remote。
mojo::PendingRemote<network::mojom::URLLoaderFactory>
LemurXLuakitMaybeCreateNavigationFactory(
    const std::string& scheme,
    content::FrameTreeNodeId frame_tree_node_id);

// ChromeContentBrowserClient::RegisterNonNetworkSubresourceURLLoaderFactories
// 调用：给已注册的每个 scheme 各挂一个工厂（页面内 <img src="luakit://…"> 等）。
void LemurXLuakitRegisterSubresourceFactories(
    int render_process_id,
    int render_frame_id,
    content::ContentBrowserClient::NonNetworkURLLoaderFactoryMap* factories);

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_SCHEME_H_
