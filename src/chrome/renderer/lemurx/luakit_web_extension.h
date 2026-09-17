// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_RENDERER_LEMURX_LUAKIT_WEB_EXTENSION_H_
#define CHROME_RENDERER_LEMURX_LUAKIT_WEB_EXTENSION_H_

#include <map>
#include <memory>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "base/memory/weak_ptr.h"
#include "chrome/common/lemurx_web.mojom.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "third_party/blink/public/common/tokens/tokens.h"
#include "v8/include/v8-forward.h"
#include "v8/include/v8-persistent-handle.h"

struct lua_State;

namespace blink {
class URLLoaderThrottle;
class WebLocalFrame;
}  // namespace blink

namespace content {
class RenderFrame;
}

namespace network {
struct ResourceRequest;
}

namespace lemurx {

// luakit 的 web extension（extension/）在 Chromium 渲染进程里的等价物。
//
// 每个渲染进程一个实例、一个 lua_State（跑在 Blink 主线程）。luakit 的
// page / dom_document / dom_element / ipc_channel / luakit / msg / soup 这些类
// 由 Lua 内核（chrome/lemurx/luakit/kernel/web/*.lua，浏览器经 ResolveModule 下发）
// 在隐藏全局表 __luakit_web 的原语之上拼出来。
//
// DOM 访问走主世界 V8：任何 JS 对象都可以变成一个整数句柄（Handle），Lua 侧用
// js_get / js_set / js_call 操作。这样 dom_element 的属性、方法、事件监听、
// 以及 eval_js 返回的函数都拿得到，和 luakit 用 JSC/WebKitDOM 的能力一一对应。
class LuakitWebExtension : public mojom::LuakitWebExtension {
 public:
  static LuakitWebExtension* Get();

  // ChromeContentRendererClient::RenderThreadStarted 调用一次
  static void Create();

  // ChromeContentRendererClient::RenderFrameCreated
  static void OnRenderFrameCreated(content::RenderFrame* frame);

  // ChromeContentRendererClient::ExposeInterfacesToBrowser
  void Bind(mojo::PendingReceiver<mojom::LuakitWebExtension> receiver);

  // URLLoaderThrottleProviderImpl::CreateThrottles（kFrame 类型、主线程）调用：
  // 该帧所属页面已被 Lua 包成 page 时返回一个节流器，把每个子资源请求同步交给
  // page 的 "send-request"(uri, headers) 信号（luakit page.c send_request_cb 语义：
  // 返回字符串 → 重定向；返回 false → 拦截；headers 表可增删改）。
  static std::unique_ptr<blink::URLLoaderThrottle> MaybeCreateURLLoaderThrottle(
      const blink::LocalFrameToken& frame_token);

  // 上面节流器的回调。返回 false 表示拦截该请求。
  bool OnSendRequest(int routing_id, network::ResourceRequest* request);

  LuakitWebExtension(const LuakitWebExtension&) = delete;
  LuakitWebExtension& operator=(const LuakitWebExtension&) = delete;

  // mojom::LuakitWebExtension
  void Init(const std::string& env_json) override;
  void RequireModule(const std::string& name) override;
  void EmitSignal(const std::string& channel,
                  int32_t page_id,
                  const std::string& signame,
                  const std::string& args_json) override;
  void PageCreated(const blink::LocalFrameToken& frame_token,
                   int32_t page_id) override;
  void EvalJs(int32_t page_id,
              const std::string& script,
              const std::string& source,
              int32_t callback_id) override;
  void Scroll(int32_t page_id, int32_t x, int32_t y) override;

  // ===== 供 FrameObserver / 原语层调用 =====

  struct Page {
    content::RenderFrame* frame = nullptr;
    int routing_id = 0;  // 渲染进程内的帧句柄（LocalFrameToken 的整数别名，Lua 侧的 page 句柄）
    int page_id = -1;   // Tab id；未知为 -1
    bool created_emitted = false;
  };

  // 由 routing_id / page_id 找页面
  Page* PageByRouting(int routing_id);
  // LocalFrameToken → 渲染进程内整数句柄（首次见到时分配）
  int RidFor(const blink::LocalFrameToken& token);
  int RidFor(content::RenderFrame* frame);
  Page* PageById(int page_id);
  content::RenderFrame* FrameForPage(int page_id);

  // 帧生命周期（FrameObserver 调）
  void OnFrameReady(content::RenderFrame* frame);
  void OnFrameDestroyed(int routing_id);
  void OnDocumentLoaded(int routing_id);
  void OnWindowObjectCleared(int routing_id);

  // JS 句柄表。LookupValue 需要活跃的 HandleScope；RoutingIdForHandle 不需要
  //（返回 0 表示无此句柄）。
  int RetainValue(int routing_id, v8::Local<v8::Value> value);
  bool LookupValue(int handle, v8::Local<v8::Value>* out, int* routing_id);
  int RoutingIdForHandle(int handle) const;
  void ReleaseValue(int handle);

  // 把 Lua 栈上的值转成 V8 值 / 反过来。均要求已经进入对应 context。
  v8::Local<v8::Value> LuaToV8(lua_State* L,
                               int idx,
                               v8::Local<v8::Context> ctx,
                               int routing_id);
  void V8ToLua(lua_State* L,
               v8::Local<v8::Value> value,
               v8::Local<v8::Context> ctx,
               int routing_id,
               int depth = 0);

  // 某个 page 的 isolate / 主世界 context；失败返回 nullptr / 空。
  // ContextFor 需要调用方已建 HandleScope。
  v8::Isolate* IsolateFor(int routing_id);
  v8::Local<v8::Context> ContextFor(int routing_id);
  // page 对应的、既没 detach 也不是 provisional 的 WebLocalFrame；否则 nullptr。
  // 任何要碰 WebDocument / V8 的地方都必须经它取帧。
  static blink::WebLocalFrame* LiveWebFrame(const Page* page);

  // C++ → Lua：调全局 __luakit_web_dispatch(kind, ...)，参数已在栈上（n 个）
  void Dispatch(const char* kind, int nargs);

  lua_State* L() const { return L_; }
  mojom::LuakitWebHost* host() { return host_.get(); }
  const std::string& env_json() const { return env_json_; }

  // Lua 回调（事件监听 / 包装函数）注册表：cb_id → 回调用 Dispatch 找 Lua 端
  int NextCallbackId() { return next_cb_id_++; }
  void StoreListener(int cb_id, int routing_id, v8::Local<v8::Function> fn);
  bool TakeListener(int cb_id, v8::Local<v8::Function>* out, int* routing_id);
  void DropListener(int cb_id);

 private:
  LuakitWebExtension();
  ~LuakitWebExtension() override;

  void EnsureState();
  void RegisterPrimitives();
  void MaybeEmitPageCreated(Page* page);

  lua_State* L_ = nullptr;
  bool inited_ = false;
  std::string env_json_;

  mojo::Receiver<mojom::LuakitWebExtension> receiver_{this};
  mojo::Remote<mojom::LuakitWebHost> host_;

  // routing_id → Page（主框架）
  std::map<int, Page> pages_;
  std::map<blink::LocalFrameToken, int> rid_by_token_;
  int next_rid_ = 1;
  // 浏览器先于帧到达的 PageCreated
  std::map<int, int> pending_page_ids_;
  // Init 前收到的 RequireModule
  std::vector<std::string> pending_requires_;

  struct Handle {
    Handle();
    Handle(Handle&&);
    Handle& operator=(Handle&&);
    ~Handle();
    v8::Global<v8::Value> value;
    int routing_id = 0;
    int identity = 0;
  };
  std::map<int, Handle> handles_;
  // (routing_id, identity hash) → handles，用来让同一个 JS 对象映射到同一个句柄
  std::map<std::pair<int, int>, std::vector<int>> identity_index_;
  int next_handle_ = 1;

  struct Listener {
    Listener();
    Listener(Listener&&);
    Listener& operator=(Listener&&);
    ~Listener();
    v8::Global<v8::Function> fn;
    int routing_id = 0;
  };
  std::map<int, Listener> listeners_;
  int next_cb_id_ = 1;

  base::WeakPtrFactory<LuakitWebExtension> weak_factory_{this};
};

}  // namespace lemurx

#endif  // CHROME_RENDERER_LEMURX_LUAKIT_WEB_EXTENSION_H_
