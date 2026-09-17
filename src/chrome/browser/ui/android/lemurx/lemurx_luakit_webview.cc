// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_luakit_webview.h"

#include <map>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/base64.h"
#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/memory/ref_counted_memory.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/utf_string_conversions.h"
#include "base/synchronization/waitable_event.h"
#include "base/time/time.h"
#include "base/timer/timer.h"
#include "base/values.h"
#include "chrome/browser/lemurx/lemurx_sync_call.h"
#include "chrome/browser/ui/android/lemurx/lemurx_cdp.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_native.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_web_host.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/navigation_throttle.h"
#include "content/public/browser/navigation_throttle_registry.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_process_host.h"
#include "content/public/browser/ssl_status.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_observer.h"
#include "content/public/common/referrer.h"
#include "content/public/common/result_codes.h"
#include "content/public/common/stop_find_action.h"
#include "net/base/net_errors.h"
#include "net/cert/cert_status_flags.h"
#include "net/cert/x509_certificate.h"
#include "third_party/blink/public/mojom/favicon/favicon_url.mojom.h"
#include "third_party/blink/public/mojom/frame/find_in_page.mojom.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"
#include "ui/base/page_transition_types.h"
#include "ui/base/window_open_disposition.h"
#include "url/gurl.h"

namespace {

// ===== UI 线程同步执行 =====
// Lua 跑在线程池 sequence 上；WebContents 只能在 UI 线程碰。
// UI 线程从不反过来等 Lua，所以这里阻塞等待不会死锁；仍然设超时兜底。
// 下面各 *OnUi 闭包都绑了调用方栈上的 &ok / &json，超时后闭包绝不能再执行，
// 这一点由 LemurXRunOnUiSync 保证（见 lemurx_sync_call.h）。

void RunOnUiSync(base::OnceClosure closure) {
  LemurXRunOnUiSync(std::move(closure), base::Seconds(8), "luakit webview");
}

std::string ToJson(const base::DictValue& dict) {
  std::string out;
  base::JSONWriter::Write(dict, &out);
  return out;
}

void PushJsonValue(lua_State* L, const base::Value& value) {
  switch (value.type()) {
    case base::Value::Type::BOOLEAN:
      lua_pushboolean(L, value.GetBool());
      break;
    case base::Value::Type::INTEGER:
      lua_pushinteger(L, value.GetInt());
      break;
    case base::Value::Type::DOUBLE:
      lua_pushnumber(L, value.GetDouble());
      break;
    case base::Value::Type::STRING:
      lua_pushlstring(L, value.GetString().data(), value.GetString().size());
      break;
    case base::Value::Type::DICT:
      lua_newtable(L);
      for (const auto item : value.GetDict()) {
        PushJsonValue(L, item.second);
        lua_setfield(L, -2, item.first.c_str());
      }
      break;
    case base::Value::Type::LIST: {
      lua_newtable(L);
      lua_Integer i = 1;
      for (const auto& child : value.GetList()) {
        PushJsonValue(L, child);
        lua_rawseti(L, -2, i++);
      }
      break;
    }
    default:
      lua_pushnil(L);
      break;
  }
}

// ===== 每个 Tab 一个观察者 =====

class TabObserver;

std::map<int, std::unique_ptr<TabObserver>>& Observers() {
  static base::NoDestructor<std::map<int, std::unique_ptr<TabObserver>>> m;
  return *m;
}

std::map<content::WebContents*, int>& TabIds() {
  static base::NoDestructor<std::map<content::WebContents*, int>> m;
  return *m;
}

std::string ReasonFor(ui::PageTransition t) {
  if (t & ui::PAGE_TRANSITION_FORWARD_BACK) {
    return "back-forward";
  }
  if (ui::PageTransitionCoreTypeIs(t, ui::PAGE_TRANSITION_RELOAD)) {
    return "reload";
  }
  if (ui::PageTransitionCoreTypeIs(t, ui::PAGE_TRANSITION_FORM_SUBMIT)) {
    return "form-submitted";
  }
  if (ui::PageTransitionCoreTypeIs(t, ui::PAGE_TRANSITION_LINK)) {
    return "link-clicked";
  }
  return "other";
}

class TabObserver : public content::WebContentsObserver {
 public:
  TabObserver(int tab_id, content::WebContents* wc)
      : content::WebContentsObserver(wc), tab_id_(tab_id) {
    TabIds()[wc] = tab_id;
    // 已有的主框架：让渲染进程 Lua 建 page 对象
    if (content::RenderFrameHost* rfh = wc->GetPrimaryMainFrame()) {
      if (rfh->IsRenderFrameLive()) {
        LemurXLuakitWebNotifyPage(rfh, tab_id_);
      }
    }
  }

  // 主框架（新进程 / 跨站导航换 RenderFrame）→ 渲染进程 page-created
  void RenderFrameCreated(content::RenderFrameHost* rfh) override {
    if (rfh && rfh->IsInPrimaryMainFrame()) {
      LemurXLuakitWebNotifyPage(rfh, tab_id_);
    }
  }

  void RenderFrameHostChanged(content::RenderFrameHost* old_host,
                              content::RenderFrameHost* new_host) override {
    if (new_host && new_host->IsInPrimaryMainFrame() &&
        new_host->IsRenderFrameLive()) {
      LemurXLuakitWebNotifyPage(new_host, tab_id_);
    }
  }
  ~TabObserver() override {
    if (web_contents()) {
      TabIds().erase(web_contents());
    }
  }

  int tab_id() const { return tab_id_; }

  void Emit(base::DictValue dict) {
    dict.Set("tab", tab_id_);
    LemurXLuakitDispatch("webview", tab_id_, ToJson(dict), 0);
  }

  void EmitLoadStatus(const std::string& status,
                      const std::string& uri = std::string(),
                      std::optional<base::DictValue> err = std::nullopt) {
    base::DictValue d;
    d.Set("ev", "load-status");
    d.Set("status", status);
    if (!uri.empty()) {
      d.Set("uri", uri);
    }
    if (err) {
      d.Set("err", std::move(*err));
    }
    Emit(std::move(d));
  }

  void EmitProperty(const std::string& name) {
    base::DictValue d;
    d.Set("ev", "property");
    d.Set("name", name);
    Emit(std::move(d));
  }

  // --- WebContentsObserver ---
  void DidStartNavigation(content::NavigationHandle* handle) override {
    if (!handle->IsInPrimaryMainFrame() || handle->IsSameDocument()) {
      return;
    }
    EmitLoadStatus("provisional", handle->GetURL().spec());
    EmitProperty("uri");
  }

  void DidRedirectNavigation(content::NavigationHandle* handle) override {
    if (!handle->IsInPrimaryMainFrame()) {
      return;
    }
    EmitLoadStatus("redirected", handle->GetURL().spec());
    EmitProperty("uri");
  }

  void DidFinishNavigation(content::NavigationHandle* handle) override {
    if (!handle->IsInPrimaryMainFrame() || handle->IsSameDocument()) {
      return;
    }
    if (handle->HasCommitted() && !handle->IsErrorPage()) {
      EmitLoadStatus("committed", handle->GetURL().spec());
      EmitProperty("uri");
      EmitProperty("title");
      return;
    }
    net::Error code = handle->GetNetErrorCode();
    if (code == net::ERR_ABORTED) {
      // luakit：stop() 之后 load-failed 的 reason 是 "cancelled"
      base::DictValue err;
      err.Set("code", static_cast<int>(code));
      err.Set("message", "cancelled");
      EmitLoadStatus("failed", handle->GetURL().spec(), std::move(err));
      return;
    }
    base::DictValue err;
    err.Set("code", static_cast<int>(code));
    err.Set("message", net::ErrorToShortString(code));
    EmitLoadStatus("failed", handle->GetURL().spec(), std::move(err));
  }

  void DidStartLoading() override { EmitProperty("is_loading"); }

  void DidStopLoading() override {
    EmitProperty("is_loading");
    EmitLoadStatus("finished");
  }

  void LoadProgressChanged(double progress) override {
    base::DictValue d;
    d.Set("ev", "progress");
    d.Set("progress", progress);
    Emit(std::move(d));
  }

  void TitleWasSet(content::NavigationEntry* entry) override {
    EmitProperty("title");
  }

  void DidUpdateFaviconURL(
      content::RenderFrameHost* rfh,
      const std::vector<blink::mojom::FaviconURLPtr>& candidates,
      blink::mojom::FaviconUpdateReason reason) override {
    if (!rfh->IsInPrimaryMainFrame()) {
      return;
    }
    base::DictValue d;
    d.Set("ev", "favicon");
    base::ListValue list;
    for (const auto& c : candidates) {
      list.Append(c->icon_url.spec());
    }
    d.Set("urls", std::move(list));
    Emit(std::move(d));
  }

  void PrimaryMainFrameRenderProcessGone(
      base::TerminationStatus status) override {
    base::DictValue d;
    d.Set("ev", "crashed");
    d.Set("status", static_cast<int>(status));
    Emit(std::move(d));
  }

  void OnAudioStateChanged(bool audible) override {
    base::DictValue d;
    d.Set("ev", "audio");
    d.Set("audible", audible);
    Emit(std::move(d));
  }

  void DidChangeVisibleSecurityState() override { EmitProperty("certificate"); }

  void DidOpenRequestedURL(content::WebContents* new_contents,
                           content::RenderFrameHost* source_rfh,
                           const GURL& url,
                           const content::Referrer& referrer,
                           WindowOpenDisposition disposition,
                           ui::PageTransition transition,
                           bool started_from_context_menu,
                           bool renderer_initiated) override {
    base::DictValue d;
    d.Set("ev", "opened-url");
    d.Set("uri", url.spec());
    d.Set("reason", ReasonFor(transition));
    auto it = TabIds().find(new_contents);
    if (it != TabIds().end()) {
      d.Set("new_tab", it->second);
    }
    Emit(std::move(d));
  }

  void WebContentsDestroyed() override {
    base::DictValue d;
    d.Set("ev", "destroyed");
    Emit(std::move(d));
    TabIds().erase(web_contents());
    // 不能在回调里 delete 自己：延后
    content::GetUIThreadTaskRunner({})->PostTask(
        FROM_HERE, base::BindOnce([](int id) { Observers().erase(id); }, tab_id_));
  }

 private:
  int tab_id_;
};

// ===== 导航节流器：navigation-request / new-window-decision 否决 =====

class Throttle;

std::map<int, base::WeakPtr<Throttle>>& PendingThrottles() {
  static base::NoDestructor<std::map<int, base::WeakPtr<Throttle>>> m;
  return *m;
}
int g_next_nav_id = 1;

class Throttle : public content::NavigationThrottle {
 public:
  Throttle(content::NavigationThrottleRegistry& registry, int tab_id)
      : content::NavigationThrottle(registry), tab_id_(tab_id) {}
  ~Throttle() override {
    if (nav_id_) {
      PendingThrottles().erase(nav_id_);
    }
  }

  ThrottleCheckResult WillStartRequest() override { return Ask(false); }
  ThrottleCheckResult WillRedirectRequest() override { return Ask(true); }
  const char* GetNameForLogging() override {
    return "LemurXLuakitNavigationThrottle";
  }

  void Reply(bool allow) {
    timeout_.Stop();
    // 只有当前仍处于 DEFER 状态（nav_id_ != 0）才能 Resume / Cancel。
    // 对一个没在 defer 的节流器调 Resume，上游 NavigationThrottleRegistry
    // 直接 CHECK；超时定时器与 Lua 回包同时到达时必须只处理一次。
    if (!nav_id_) {
      return;
    }
    PendingThrottles().erase(nav_id_);
    nav_id_ = 0;
    if (allow) {
      Resume();
    } else {
      CancelDeferredNavigation(content::NavigationThrottle::CANCEL_AND_IGNORE);
    }
  }

 private:
  ThrottleCheckResult Ask(bool redirect) {
    content::NavigationHandle* h = navigation_handle();
    if (nav_id_) {
      // 上一轮（如 WillStartRequest）尚未回复就进入下一轮：先清掉旧登记，
      // 旧 id 的回包按“已过期”忽略。
      PendingThrottles().erase(nav_id_);
      timeout_.Stop();
    }
    nav_id_ = g_next_nav_id++;
    PendingThrottles()[nav_id_] = weak_factory_.GetWeakPtr();

    base::DictValue d;
    d.Set("ev", "navigation-request");
    d.Set("id", nav_id_);
    d.Set("uri", h->GetURL().spec());
    d.Set("reason", ReasonFor(h->GetPageTransition()));
    d.Set("main_frame", h->IsInPrimaryMainFrame());
    d.Set("redirect", redirect);
    d.Set("renderer_initiated", h->IsRendererInitiated());
    d.Set("user_gesture", h->HasUserGesture());
    d.Set("tab", tab_id_);
    LemurXLuakitDispatch("webview", tab_id_, ToJson(d), nav_id_);

    // Lua 不回话（脚本出错等）时 4 秒后放行，别把页面卡死
    timeout_.Start(FROM_HERE, base::Seconds(4),
                   base::BindOnce(&Throttle::Reply, weak_factory_.GetWeakPtr(),
                                  true));
    return DEFER;
  }

  int tab_id_;
  int nav_id_ = 0;
  base::OneShotTimer timeout_;
  base::WeakPtrFactory<Throttle> weak_factory_{this};
};

void NavigationReplyOnUi(int nav_id, bool allow) {
  auto it = PendingThrottles().find(nav_id);
  if (it == PendingThrottles().end()) {
    return;
  }
  base::WeakPtr<Throttle> t = it->second;
  PendingThrottles().erase(it);
  if (t) {
    t->Reply(allow);
  }
}

// ===== 证书放行表 =====
std::map<std::string, std::string>& AllowedCerts() {
  static base::NoDestructor<std::map<std::string, std::string>> m;
  return *m;
}

// ===== Tab 级操作（UI 线程） =====

content::WebContents* WcOnUi(int tab_id) {
  return LemurXWebContentsForTab(tab_id);
}

void AttachOnUi(int tab_id, bool* ok) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    *ok = false;
    return;
  }
  auto& obs = Observers();
  auto it = obs.find(tab_id);
  if (it == obs.end() || it->second->web_contents() != wc) {
    obs[tab_id] = std::make_unique<TabObserver>(tab_id, wc);
  }
  *ok = true;
}

void DetachOnUi(int tab_id) {
  Observers().erase(tab_id);
}

void LoadStringOnUi(int tab_id, std::string html, std::string uri) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  std::string b64 = base::Base64Encode(html);
  GURL data_url("data:text/html;charset=utf-8;base64," + b64);
  content::NavigationController::LoadURLParams params(data_url);
  params.load_type = content::NavigationController::LOAD_TYPE_DATA;
  GURL shown(uri);
  if (shown.is_valid()) {
    params.base_url_for_data_url = shown;
    params.virtual_url_for_special_cases = shown;  // 154: virtual_url_for_data_url 改名
  }
  params.transition_type = ui::PAGE_TRANSITION_TYPED;
  wc->GetController().LoadURLWithParams(params);
}

int g_next_find_id = 1;

void FindOnUi(int tab_id,
              std::u16string text,
              bool match_case,
              bool forward,
              bool new_session) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc || text.empty()) {
    return;
  }
  auto options = blink::mojom::FindOptions::New();
  options->forward = forward;
  options->match_case = match_case;
  options->new_session = new_session;
  wc->Find(g_next_find_id++, text, std::move(options), /*skip_delay=*/true);
}

void StopFindOnUi(int tab_id, bool keep) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  wc->StopFinding(keep ? content::STOP_FIND_ACTION_KEEP_SELECTION
                       : content::STOP_FIND_ACTION_CLEAR_SELECTION);
}

void HistoryOnUi(int tab_id, std::string* json) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  content::NavigationController& c = wc->GetController();
  base::DictValue d;
  d.Set("index", c.GetCurrentEntryIndex() + 1);  // luakit 是 1-based
  base::ListValue items;
  for (int i = 0; i < c.GetEntryCount(); ++i) {
    content::NavigationEntry* e = c.GetEntryAtIndex(i);
    base::DictValue item;
    item.Set("uri", e->GetVirtualURL().spec());
    item.Set("title", base::UTF16ToUTF8(e->GetTitle()));
    items.Append(std::move(item));
  }
  d.Set("items", std::move(items));
  *json = ToJson(d);
}

void RestoreOnUi(int tab_id, std::string state) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  std::optional<base::Value> v = base::JSONReader::Read(state, base::JSON_PARSE_RFC);
  if (!v || !v->is_dict()) {
    return;
  }
  const base::DictValue& d = v->GetDict();
  const base::ListValue* items = d.FindList("items");
  int index = d.FindInt("index").value_or(0);
  if (!items || items->empty()) {
    return;
  }
  if (index < 1 || index > static_cast<int>(items->size())) {
    index = static_cast<int>(items->size());
  }
  const base::DictValue* cur = (*items)[index - 1].GetIfDict();
  if (!cur) {
    return;
  }
  const std::string* uri = cur->FindString("uri");
  if (!uri) {
    return;
  }
  GURL url(*uri);
  if (!url.is_valid()) {
    return;
  }
  content::NavigationController::LoadURLParams params(url);
  params.transition_type = ui::PAGE_TRANSITION_RELOAD;
  wc->GetController().LoadURLWithParams(params);
}

void CertificateOnUi(int tab_id, std::string* pem, int* trusted) {
  // trusted: -1 = 不是 https / 没证书, 0 = 有问题, 1 = 可信
  *trusted = -1;
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  content::NavigationEntry* e = wc->GetController().GetVisibleEntry();
  if (!e) {
    return;
  }
  const content::SSLStatus& ssl = e->GetSSL();
  if (!ssl.certificate) {
    return;
  }
  std::vector<std::string> chain;
  if (ssl.certificate->GetPEMEncodedChain(&chain) && !chain.empty()) {
    for (const auto& c : chain) {
      *pem += c;
    }
  }
  *trusted = net::IsCertStatusError(ssl.cert_status) ? 0 : 1;
}

void InfoOnUi(int tab_id, std::string* json) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  base::DictValue d;
  d.Set("uri", wc->GetVisibleURL().spec());
  d.Set("title", base::UTF16ToUTF8(wc->GetTitle()));
  d.Set("is_loading", wc->IsLoading());
  d.Set("progress", wc->GetLoadProgress());
  d.Set("audible", wc->IsCurrentlyAudible());
  d.Set("can_go_back", wc->GetController().CanGoBack());
  d.Set("can_go_forward", wc->GetController().CanGoForward());
  content::RenderFrameHost* rfh = wc->GetPrimaryMainFrame();
  if (rfh && rfh->GetProcess()) {
    d.Set("process_id", rfh->GetProcess()->GetDeprecatedID());
    d.Set("os_pid", static_cast<int>(rfh->GetProcess()->GetProcess().Pid()));
  }
  d.Set("incognito", wc->GetBrowserContext() &&
                         wc->GetBrowserContext()->IsOffTheRecord());
  *json = ToJson(d);
}

void CrashOnUi(int tab_id) {
  content::WebContents* wc = WcOnUi(tab_id);
  if (!wc) {
    return;
  }
  content::RenderFrameHost* rfh = wc->GetPrimaryMainFrame();
  if (rfh && rfh->GetProcess()) {
    rfh->GetProcess()->Shutdown(content::RESULT_CODE_KILLED);
  }
}

void GoOffsetOnUi(int tab_id, int offset, bool* ok) {
  content::WebContents* wc = WcOnUi(tab_id);
  *ok = false;
  if (!wc) {
    return;
  }
  content::NavigationController& c = wc->GetController();
  if (!c.CanGoToOffset(offset)) {
    return;
  }
  c.GoToOffset(offset);
  *ok = true;
}

// ===== Lua 绑定 =====

int TabArg(lua_State* L, int idx) {
  return static_cast<int>(luaL_checkinteger(L, idx));
}

int WvAttach(lua_State* L) {
  int tab = TabArg(L, 1);
  bool ok = false;
  RunOnUiSync(base::BindOnce(&AttachOnUi, tab, &ok));
  lua_pushboolean(L, ok);
  return 1;
}

int WvDetach(lua_State* L) {
  int tab = TabArg(L, 1);
  RunOnUiSync(base::BindOnce(&DetachOnUi, tab));
  return 0;
}

int WvLoadString(lua_State* L) {
  int tab = TabArg(L, 1);
  size_t len = 0;
  const char* html = luaL_checklstring(L, 2, &len);
  const char* uri = luaL_optstring(L, 3, "about:blank");
  RunOnUiSync(
      base::BindOnce(&LoadStringOnUi, tab, std::string(html, len), std::string(uri)));
  return 0;
}

int WvFind(lua_State* L) {
  int tab = TabArg(L, 1);
  const char* text = luaL_checkstring(L, 2);
  bool match_case = lua_toboolean(L, 3);
  bool forward = lua_isnoneornil(L, 4) ? true : lua_toboolean(L, 4);
  bool new_session = lua_isnoneornil(L, 5) ? true : lua_toboolean(L, 5);
  RunOnUiSync(base::BindOnce(&FindOnUi, tab, base::UTF8ToUTF16(text), match_case,
                             forward, new_session));
  return 0;
}

int WvStopFind(lua_State* L) {
  int tab = TabArg(L, 1);
  bool keep = lua_toboolean(L, 2);
  RunOnUiSync(base::BindOnce(&StopFindOnUi, tab, keep));
  return 0;
}

int WvHistory(lua_State* L) {
  int tab = TabArg(L, 1);
  std::string json;
  RunOnUiSync(base::BindOnce(&HistoryOnUi, tab, &json));
  if (json.empty()) {
    lua_pushnil(L);
    return 1;
  }
  std::optional<base::Value> v = base::JSONReader::Read(json, base::JSON_PARSE_RFC);
  if (!v) {
    lua_pushnil(L);
    return 1;
  }
  PushJsonValue(L, *v);
  return 1;
}

int WvSessionState(lua_State* L) {
  int tab = TabArg(L, 1);
  std::string json;
  RunOnUiSync(base::BindOnce(&HistoryOnUi, tab, &json));
  lua_pushlstring(L, json.data(), json.size());
  return 1;
}

int WvRestoreSessionState(lua_State* L) {
  int tab = TabArg(L, 1);
  size_t len = 0;
  const char* s = luaL_checklstring(L, 2, &len);
  RunOnUiSync(base::BindOnce(&RestoreOnUi, tab, std::string(s, len)));
  return 0;
}

int WvCertificate(lua_State* L) {
  int tab = TabArg(L, 1);
  std::string pem;
  int trusted = -1;
  RunOnUiSync(base::BindOnce(&CertificateOnUi, tab, &pem, &trusted));
  if (trusted < 0) {
    lua_pushnil(L);
    lua_pushnil(L);
    return 2;
  }
  lua_pushlstring(L, pem.data(), pem.size());
  lua_pushboolean(L, trusted == 1);
  return 2;
}

int WvInfo(lua_State* L) {
  int tab = TabArg(L, 1);
  std::string json;
  RunOnUiSync(base::BindOnce(&InfoOnUi, tab, &json));
  if (json.empty()) {
    lua_pushnil(L);
    return 1;
  }
  std::optional<base::Value> v = base::JSONReader::Read(json, base::JSON_PARSE_RFC);
  if (!v) {
    lua_pushnil(L);
    return 1;
  }
  PushJsonValue(L, *v);
  return 1;
}

int WvCrash(lua_State* L) {
  int tab = TabArg(L, 1);
  RunOnUiSync(base::BindOnce(&CrashOnUi, tab));
  return 0;
}

int WvGoOffset(lua_State* L) {
  int tab = TabArg(L, 1);
  int offset = static_cast<int>(luaL_checkinteger(L, 2));
  bool ok = false;
  RunOnUiSync(base::BindOnce(&GoOffsetOnUi, tab, offset, &ok));
  lua_pushboolean(L, ok);
  return 1;
}

int WvNavigationReply(lua_State* L) {
  int nav_id = static_cast<int>(luaL_checkinteger(L, 1));
  bool allow = lua_toboolean(L, 2);
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(&NavigationReplyOnUi, nav_id, allow));
  return 0;
}

int WvAllowCertificate(lua_State* L) {
  const char* host = luaL_checkstring(L, 1);
  size_t len = 0;
  const char* pem = luaL_optlstring(L, 2, "", &len);
  std::string h = host;
  std::string p(pem, len);
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(
                     [](std::string host, std::string pem) {
                       AllowedCerts()[host] = pem;
                     },
                     h, p));
  return 0;
}

void SetFn(lua_State* L, const char* name, lua_CFunction fn) {
  lua_pushcfunction(L, fn);
  lua_setfield(L, -2, name);
}

}  // namespace

void LemurXLuakitMaybeAddNavigationThrottle(
    content::NavigationThrottleRegistry& registry) {
  content::WebContents* wc = registry.GetNavigationHandle().GetWebContents();
  auto it = TabIds().find(wc);
  if (it == TabIds().end()) {
    return;
  }
  registry.AddThrottle(std::make_unique<Throttle>(registry, it->second));
}

bool LemurXLuakitIsCertificateAllowed(std::string_view host) {
  return AllowedCerts().count(std::string(host)) > 0;
}

int LemurXLuakitTabIdForWebContents(content::WebContents* web_contents) {
  auto it = TabIds().find(web_contents);
  return it == TabIds().end() ? -1 : it->second;
}

void LemurXLuakitWebviewResetAll() {
  // 正在 DEFER 等 Lua 回话的导航：Lua 已经没了，全部放行，别让页面卡到 4 秒超时
  std::vector<base::WeakPtr<Throttle>> pending;
  for (auto& item : PendingThrottles()) {
    pending.push_back(item.second);
  }
  PendingThrottles().clear();
  for (auto& t : pending) {
    if (t) {
      t->Reply(true);
    }
  }
  // 摘掉全部附着：TabObserver 析构会把自己从 TabIds() 里移除；
  // 之后 MaybeAddNavigationThrottle 对任何 Tab 都不再挂节流器
  Observers().clear();
  TabIds().clear();
  AllowedCerts().clear();
}

void RegisterLemurXLuakitWebview(lua_State* L) {
  lua_getglobal(L, "__luakit");
  if (!lua_istable(L, -1)) {
    lua_pop(L, 1);
    return;
  }
  SetFn(L, "wv_attach", WvAttach);
  SetFn(L, "wv_detach", WvDetach);
  SetFn(L, "wv_load_string", WvLoadString);
  SetFn(L, "wv_find", WvFind);
  SetFn(L, "wv_stop_find", WvStopFind);
  SetFn(L, "wv_history", WvHistory);
  SetFn(L, "wv_session_state", WvSessionState);
  SetFn(L, "wv_restore_session_state", WvRestoreSessionState);
  SetFn(L, "wv_certificate", WvCertificate);
  SetFn(L, "wv_info", WvInfo);
  SetFn(L, "wv_crash", WvCrash);
  SetFn(L, "wv_go_offset", WvGoOffset);
  SetFn(L, "wv_navigation_reply", WvNavigationReply);
  SetFn(L, "wv_allow_certificate", WvAllowCertificate);
  lua_pop(L, 1);
}
