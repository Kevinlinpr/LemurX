// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_luakit_web_host.h"

#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/functional/bind.h"
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/string_util.h"
#include "base/synchronization/lock.h"
#include "base/task/thread_pool.h"
#include "base/time/time.h"
#include "base/values.h"
#include "chrome/browser/lemurx/lemurx_sync_call.h"
#include "chrome/browser/ui/android/lemurx/lemurx_cdp.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_native.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"
#include "third_party/blink/public/common/tokens/tokens.h"
#include "content/public/browser/render_process_host.h"
#include "content/public/browser/web_contents.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"

namespace {

// ===== 跨线程共享状态（Lua 线程写，UI 线程读） =====

struct Shared {
  base::Lock lock;
  std::vector<std::string> modules;  // require_web_module 过的模块，按顺序
  std::string install_dir;
  std::string config_dir;
  std::string env_json;
};

Shared& GetShared() {
  static base::NoDestructor<Shared> s;
  return *s;
}

void EnsureEnv() {
  Shared& sh = GetShared();
  base::AutoLock lock(sh.lock);
  if (!sh.env_json.empty()) {
    return;
  }
  sh.env_json = LemurXLuakitEnvJson();
  std::optional<base::Value> v = base::JSONReader::Read(sh.env_json, base::JSON_PARSE_RFC);
  if (v && v->is_dict()) {
    const std::string* inst = v->GetDict().FindString("install_dir");
    const std::string* conf = v->GetDict().FindString("config_dir");
    if (inst) {
      sh.install_dir = *inst;
    }
    if (conf) {
      sh.config_dir = *conf;
    }
  }
}

// 线程池：按顺序找第一个存在的文件，返回 (source, path)；都没有则 path 为空
std::pair<std::string, std::string> ReadFirstExisting(
    std::vector<std::string> candidates) {
  for (const std::string& path : candidates) {
    base::FilePath fp(path);
    std::string source;
    if (base::PathExists(fp) && base::ReadFileToString(fp, &source)) {
      return {std::move(source), path};
    }
  }
  return {std::string(), std::string()};
}

// ===== 每个渲染进程一个 Host（UI 线程） =====

class WebHost;

std::map<int, std::unique_ptr<WebHost>>& Hosts() {
  static base::NoDestructor<std::map<int, std::unique_ptr<WebHost>>> m;
  return *m;
}

// 主框架的身份：LocalFrameToken（154 起渲染进程侧没有 routing id 了）
using FrameRef = blink::LocalFrameToken;

// 渲染进程 Ready 之前到达的主框架通知：pid → [(frame_token, tab_id)]
std::map<int, std::vector<std::pair<FrameRef, int>>>& PendingNotifies() {
  static base::NoDestructor<std::map<int, std::vector<std::pair<FrameRef, int>>>>
      m;
  return *m;
}

std::string ToJson(const base::DictValue& d) {
  std::string out;
  base::JSONWriter::Write(d, &out);
  return out;
}

class WebHost : public lemurx::mojom::LuakitWebHost {
 public:
  WebHost(int process_id,
          mojo::PendingReceiver<lemurx::mojom::LuakitWebHost> receiver)
      : process_id_(process_id), receiver_(this, std::move(receiver)) {
    receiver_.set_disconnect_handler(
        base::BindOnce(&WebHost::OnDisconnect, weak_factory_.GetWeakPtr()));
  }
  ~WebHost() override = default;

  int process_id() const { return process_id_; }
  bool ready() const { return ready_; }
  lemurx::mojom::LuakitWebExtension* ext() {
    return ext_.is_bound() ? ext_.get() : nullptr;
  }

  void NotifyPage(const FrameRef& frame, int tab_id) {
    if (ext()) {
      ext_->PageCreated(frame, tab_id);
      NotifyPageAttached(tab_id);
    } else {
      pending_pages_.emplace_back(frame, tab_id);
    }
  }

  // luakit：某个 view 接上了 web extension → luakit "web-extension-created"(view)
  // + view "web-extension-loaded"
  void NotifyPageAttached(int tab_id) {
    base::DictValue d;
    d.Set("ev", "page");
    d.Set("pid", process_id_);
    d.Set("tab", tab_id);
    LemurXLuakitDispatch("webext", process_id_, ToJson(d), 0);
  }

  // lemurx::mojom::LuakitWebHost
  void Ready() override {
    if (ext_.is_bound() || disconnected_) {
      // 渲染进程重复发 Ready：再次 BindNewPipeAndPassReceiver 会 CHECK
      return;
    }
    content::RenderProcessHost* rph =
        content::RenderProcessHost::FromID(process_id_);
    if (!rph) {
      return;
    }
    rph->BindReceiver(ext_.BindNewPipeAndPassReceiver());
    ext_.set_disconnect_handler(
        base::BindOnce(&WebHost::OnDisconnect, weak_factory_.GetWeakPtr()));
    EnsureEnv();
    std::vector<std::string> modules;
    std::string env;
    {
      Shared& sh = GetShared();
      base::AutoLock lock(sh.lock);
      modules = sh.modules;
      env = sh.env_json;
    }
    // 渲染进程侧 luakit.web_process_id：与 UI 侧 webview.web_process_id 同一套 id
    {
      std::optional<base::Value> ev = base::JSONReader::Read(env, base::JSON_PARSE_RFC);
      base::DictValue d = ev && ev->is_dict() ? std::move(ev->GetDict())
                                                 : base::DictValue();
      d.Set("pid", process_id_);
      env = ToJson(d);
    }
    ext_->Init(env);
    for (const std::string& m : modules) {
      ext_->RequireModule(m);
    }
    ready_ = true;

    base::DictValue d;
    d.Set("ev", "created");
    d.Set("pid", process_id_);
    LemurXLuakitDispatch("webext", process_id_, ToJson(d), 0);

    std::vector<std::pair<FrameRef, int>> pending = std::move(pending_pages_);
    pending_pages_.clear();
    for (const auto& [frame, tab] : pending) {
      ext_->PageCreated(frame, tab);
      NotifyPageAttached(tab);
    }
  }

  void EmitSignal(const std::string& channel,
                  const std::string& signame,
                  const std::string& args_json) override {
    base::DictValue d;
    d.Set("channel", channel);
    d.Set("signame", signame);
    d.Set("args", args_json);
    d.Set("pid", process_id_);
    LemurXLuakitDispatch("webipc", process_id_, ToJson(d), 0);
  }

  void ResolveModule(const std::string& name,
                     ResolveModuleCallback callback) override {
    EnsureEnv();
    std::string install, config;
    {
      Shared& sh = GetShared();
      base::AutoLock lock(sh.lock);
      install = sh.install_dir;
      config = sh.config_dir;
    }
    std::string rel = name;
    base::ReplaceChars(rel, ".", "/", &rel);
    if (rel.find("..") != std::string::npos) {
      std::move(callback).Run(false, std::string(), std::string());
      return;
    }
    std::vector<std::string> candidates = {
        install + "/kernel/web/" + rel + ".lua",
        install + "/kernel/" + rel + ".lua",
        config + "/" + rel + ".lua",
        config + "/" + rel + "/init.lua",
        install + "/lib/" + rel + ".lua",
        install + "/lib/" + rel + "/init.lua",
    };
    // 文件 IO 放线程池；[Sync] 调用允许异步回包，渲染进程那边照旧阻塞等
    base::ThreadPool::PostTaskAndReplyWithResult(
        FROM_HERE, {base::MayBlock(), base::TaskPriority::USER_BLOCKING},
        base::BindOnce(&ReadFirstExisting, std::move(candidates)),
        base::BindOnce(
            [](ResolveModuleCallback cb,
               std::pair<std::string, std::string> found) {
              std::move(cb).Run(!found.second.empty(), std::move(found.first),
                                std::move(found.second));
            },
            std::move(callback)));
  }

  void Log(int32_t level,
           const std::string& group,
           const std::string& message) override {
    base::DictValue d;
    d.Set("level", level);
    d.Set("group", group);
    d.Set("msg", message);
    d.Set("pid", process_id_);
    LemurXLuakitDispatch("weblog", level, ToJson(d), process_id_);
  }

  void EvalJsResult(int32_t callback_id,
                    const std::string& result_json,
                    const std::optional<std::string>& error) override {
    base::DictValue d;
    d.Set("result", result_json);
    if (error) {
      d.Set("error", *error);
    }
    LemurXLuakitDispatch("webeval", callback_id, ToJson(d), 0);
  }

 private:
  // receiver_ 与 ext_ 两条管道都挂了这个回调；渲染进程退出时两条几乎同时断。
  // 只处理第一次：先把两端都 reset（不会再有第二次回调），再把删除自己
  // 延后到下一个任务——不在 mojo 的回调栈上 delete this。
  void OnDisconnect() {
    if (disconnected_) {
      return;
    }
    disconnected_ = true;
    ready_ = false;
    receiver_.reset();
    ext_.reset();
    base::DictValue d;
    d.Set("ev", "destroyed");
    d.Set("pid", process_id_);
    LemurXLuakitDispatch("webext", process_id_, ToJson(d), 0);
    content::GetUIThreadTaskRunner({})->PostTask(
        FROM_HERE, base::BindOnce(
                       [](int pid) {
                         auto it = Hosts().find(pid);
                         // 同一 pid 若已被新的 Host 顶替，不能误删新的
                         if (it != Hosts().end() && it->second->disconnected_) {
                           Hosts().erase(it);
                         }
                       },
                       process_id_));
  }

  const int process_id_;
  bool ready_ = false;
  bool disconnected_ = false;
  mojo::Receiver<lemurx::mojom::LuakitWebHost> receiver_;
  mojo::Remote<lemurx::mojom::LuakitWebExtension> ext_;
  std::vector<std::pair<FrameRef, int>> pending_pages_;
  base::WeakPtrFactory<WebHost> weak_factory_{this};
};

// ===== UI 线程操作 =====

// tab → (process id, main-frame token)；失败返回 false
bool MainFrameOf(int tab_id, int* pid, FrameRef* rid) {
  content::WebContents* wc = LemurXWebContentsForTab(tab_id);
  if (!wc) {
    return false;
  }
  content::RenderFrameHost* rfh = wc->GetPrimaryMainFrame();
  if (!rfh || !rfh->GetProcess()) {
    return false;
  }
  *pid = rfh->GetProcess()->GetDeprecatedID();
  *rid = rfh->GetFrameToken();
  return true;
}

void RequireOnUi(std::string name) {
  for (auto& [pid, host] : Hosts()) {
    if (host->ext()) {
      host->ext()->RequireModule(name);
    }
  }
}

void EmitOnUi(std::string channel,
              int tab_id,
              std::string signame,
              std::string json) {
  if (tab_id < 0) {
    for (auto& [pid, host] : Hosts()) {
      if (host->ext()) {
        host->ext()->EmitSignal(channel, -1, signame, json);
      }
    }
    return;
  }
  int pid = 0;
  FrameRef rid;
  if (!MainFrameOf(tab_id, &pid, &rid)) {
    return;
  }
  auto it = Hosts().find(pid);
  if (it == Hosts().end() || !it->second->ext()) {
    return;
  }
  // 顺手确保该页面在渲染进程里已知（进程刚起来时 TabObserver 可能还没通知）
  it->second->NotifyPage(rid, tab_id);
  it->second->ext()->EmitSignal(channel, tab_id, signame, json);
}

void EvalOnUi(int tab_id, std::string script, std::string source, int cb_id) {
  int pid = 0;
  FrameRef rid;
  if (!MainFrameOf(tab_id, &pid, &rid)) {
    base::DictValue d;
    d.Set("result", "null");
    d.Set("error", "no such tab");
    LemurXLuakitDispatch("webeval", cb_id, ToJson(d), 0);
    return;
  }
  auto it = Hosts().find(pid);
  if (it == Hosts().end() || !it->second->ext()) {
    base::DictValue d;
    d.Set("result", "null");
    d.Set("error", "web extension not attached to this process");
    LemurXLuakitDispatch("webeval", cb_id, ToJson(d), 0);
    return;
  }
  it->second->NotifyPage(rid, tab_id);
  it->second->ext()->EvalJs(tab_id, script, source, cb_id);
}

void ScrollOnUi(int tab_id, int x, int y) {
  int pid = 0;
  FrameRef rid;
  if (!MainFrameOf(tab_id, &pid, &rid)) {
    return;
  }
  auto it = Hosts().find(pid);
  if (it == Hosts().end() || !it->second->ext()) {
    return;
  }
  it->second->NotifyPage(rid, tab_id);
  it->second->ext()->Scroll(tab_id, x, y);
}

void ProcessesOnUi(std::vector<int>* out) {
  for (auto& [pid, host] : Hosts()) {
    if (host->ready()) {
      out->push_back(pid);
    }
  }
}

// ===== Lua 原语（Lua 线程） =====

void PostUi(base::OnceClosure c) {
  content::GetUIThreadTaskRunner({})->PostTask(FROM_HERE, std::move(c));
}

// __luakit.web_require(name)
int WebRequire(lua_State* L) {
  std::string name = luaL_checkstring(L, 1);
  {
    Shared& sh = GetShared();
    base::AutoLock lock(sh.lock);
    for (const std::string& m : sh.modules) {
      if (m == name) {
        return 0;  // 已登记；新进程起来会自动 require
      }
    }
    sh.modules.push_back(name);
  }
  PostUi(base::BindOnce(&RequireOnUi, name));
  return 0;
}

// __luakit.web_emit(channel, tab_id|-1, signame, args_json)
int WebEmit(lua_State* L) {
  std::string channel = luaL_checkstring(L, 1);
  int tab = static_cast<int>(luaL_optinteger(L, 2, -1));
  std::string signame = luaL_checkstring(L, 3);
  std::string json = luaL_optstring(L, 4, "[]");
  PostUi(base::BindOnce(&EmitOnUi, channel, tab, signame, json));
  return 0;
}

// __luakit.web_eval(tab_id, script, source, cb_id)
int WebEval(lua_State* L) {
  int tab = static_cast<int>(luaL_checkinteger(L, 1));
  size_t len = 0;
  const char* s = luaL_checklstring(L, 2, &len);
  std::string source = luaL_optstring(L, 3, "(lua)");
  int cb = static_cast<int>(luaL_checkinteger(L, 4));
  PostUi(base::BindOnce(&EvalOnUi, tab, std::string(s, len), source, cb));
  return 0;
}

// __luakit.web_scroll(tab_id, x, y)
int WebScroll(lua_State* L) {
  int tab = static_cast<int>(luaL_checkinteger(L, 1));
  int x = static_cast<int>(luaL_checkinteger(L, 2));
  int y = static_cast<int>(luaL_checkinteger(L, 3));
  PostUi(base::BindOnce(&ScrollOnUi, tab, x, y));
  return 0;
}

// __luakit.web_processes() -> { pid, ... }（同步问 UI 线程；超时返回空表，
// 且超时后 UI 侧闭包不会再执行——pids 在栈上）
int WebProcesses(lua_State* L) {
  std::vector<int> pids;
  LemurXRunOnUiSync(base::BindOnce(&ProcessesOnUi, &pids), base::Seconds(5),
                    "luakit web_processes");
  lua_newtable(L);
  lua_Integer i = 1;
  for (int pid : pids) {
    lua_pushinteger(L, pid);
    lua_rawseti(L, -2, i++);
  }
  return 1;
}

void SetFn(lua_State* L, const char* name, lua_CFunction fn) {
  lua_pushcfunction(L, fn);
  lua_setfield(L, -2, name);
}

}  // namespace

void LemurXLuakitBindWebHost(
    int render_process_id,
    mojo::PendingReceiver<lemurx::mojom::LuakitWebHost> receiver) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto host = std::make_unique<WebHost>(render_process_id, std::move(receiver));
  // 渲染进程连上来之前浏览器就可能已经建了它的主框架：补发
  auto pending = PendingNotifies().find(render_process_id);
  if (pending != PendingNotifies().end()) {
    for (const auto& [rid, tab] : pending->second) {
      host->NotifyPage(rid, tab);
    }
    PendingNotifies().erase(pending);
  }
  Hosts()[render_process_id] = std::move(host);
}

void LemurXLuakitWebNotifyPage(content::RenderFrameHost* main_frame,
                              int tab_id) {
  if (!main_frame || !main_frame->GetProcess()) {
    return;
  }
  int pid = main_frame->GetProcess()->GetDeprecatedID();
  FrameRef rid = main_frame->GetFrameToken();
  auto it = Hosts().find(pid);
  if (it == Hosts().end()) {
    PendingNotifies()[pid].emplace_back(rid, tab_id);
    return;
  }
  it->second->NotifyPage(rid, tab_id);
}

void LemurXLuakitWebHostResetAll() {
  // 新起的渲染进程不再自动 require 任何 web 模块；尚未送达的主框架通知作废。
  // 已经在某个渲染进程里跑起来的模块随该进程（页面导航/关闭）自然消亡——
  // 渲染进程侧没有「卸载」原语，而它们只对 TabObserver 报过 PageCreated 的页
  // 面生效，这里 TabObserver 已被 LemurXLuakitWebviewResetAll 清光。
  {
    Shared& sh = GetShared();
    base::AutoLock lock(sh.lock);
    sh.modules.clear();
  }
  PendingNotifies().clear();
}

void RegisterLemurXLuakitWebHost(lua_State* L) {
  lua_getglobal(L, "__luakit");
  if (!lua_istable(L, -1)) {
    lua_pop(L, 1);
    return;
  }
  SetFn(L, "web_require", WebRequire);
  SetFn(L, "web_emit", WebEmit);
  SetFn(L, "web_eval", WebEval);
  SetFn(L, "web_scroll", WebScroll);
  SetFn(L, "web_processes", WebProcesses);
  lua_pop(L, 1);
}
