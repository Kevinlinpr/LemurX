// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_cdp.h"

#include <cstring>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "base/containers/span.h"
#include "base/functional/bind.h"
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/memory/ref_counted.h"
#include "base/memory/scoped_refptr.h"
#include "base/no_destructor.h"
#include "base/synchronization/waitable_event.h"
#include "base/time/time.h"
#include "base/values.h"
#include "chrome/browser/ui/android/lemurx/lemurx_api.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/devtools_agent_host.h"
#include "content/public/browser/devtools_agent_host_client.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "url/gurl.h"

namespace {

class Pending : public base::RefCountedThreadSafe<Pending> {
 public:
  Pending()
      : event(base::WaitableEvent::ResetPolicy::MANUAL,
              base::WaitableEvent::InitialState::NOT_SIGNALED) {}

  base::WaitableEvent event;
  std::string json;

 private:
  friend class base::RefCountedThreadSafe<Pending>;
  ~Pending() = default;
};

class LemurXCdpClient : public content::DevToolsAgentHostClient {
 public:
  explicit LemurXCdpClient(int tab_id) : tab_id_(tab_id) {}

  ~LemurXCdpClient() override {
    if (host_) {
      host_->DetachClient(this);
    }
  }

  bool Attach(content::WebContents* web_contents) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!web_contents) {
      return false;
    }
    if (attached_ && host_ && host_->GetWebContents() == web_contents) {
      return true;
    }
    if (host_) {
      host_->DetachClient(this);
      host_.reset();
      attached_ = false;
    }
    host_ = content::DevToolsAgentHost::GetOrCreateFor(web_contents);
    if (!host_ || !host_->AttachClient(this)) {
      host_.reset();
      attached_ = false;
      return false;
    }
    attached_ = true;
    EnableDefaults();
    return true;
  }

  bool AttachHost(scoped_refptr<content::DevToolsAgentHost> host) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!host) {
      return false;
    }
    if (attached_ && host_ && host_->GetId() == host->GetId()) {
      return true;
    }
    if (host_) {
      host_->DetachClient(this);
      host_.reset();
      attached_ = false;
    }
    host_ = host;
    if (!host_->AttachClient(this)) {
      host_.reset();
      attached_ = false;
      return false;
    }
    attached_ = true;
    EnableDefaults();
    return true;
  }

  bool HasHost() const { return attached_ && host_; }

  void Detach() {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    FailAll("{\"id\":0,\"error\":{\"message\":\"detached\"},\"ok\":false}");
    if (host_) {
      host_->DetachClient(this);
      host_.reset();
    }
    attached_ = false;
    enabled_domains_.clear();
  }

    bool Send(const std::string& method,
            const std::string& params_json,
            const std::string& session_id,
            scoped_refptr<Pending> pending) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!host_) {
      pending->json =
          "{\"ok\":false,\"error\":\"not attached, call lemurx.cdp.attach first\"}";
      pending->event.Signal();
      return false;
    }
    // 之前把 Overlay / 设备度量整个拒掉是误判：崩页真凶是 world_id=0 注入，
    // 这两块跟 chrome://inspect 远程调试走的是同一条路，放开。
    MaybeEnable(method);
    int id = ++next_id_;
    waiters_[id] = pending;
    base::Value::Dict msg;
    msg.Set("id", id);
    msg.Set("method", method);
    if (!session_id.empty()) {
      msg.Set("sessionId", session_id);
    }
    if (!params_json.empty() && params_json != "{}" && params_json != "null") {
      std::optional<base::Value> params = base::JSONReader::Read(params_json);
      if (params) {
        msg.Set("params", std::move(*params));
      }
    }
    std::string json;
    base::JSONWriter::Write(msg, &json);
    host_->DispatchProtocolMessage(this, base::as_bytes(base::make_span(json)));
    return true;
  }

  bool Inspect(int x, int y) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!host_) {
      return false;
    }
    content::WebContents* wc = host_->GetWebContents();
    content::RenderFrameHost* frame = wc ? wc->GetPrimaryMainFrame() : nullptr;
    if (!frame) {
      return false;
    }
    host_->InspectElement(frame, x, y);
    return true;
  }

  void DispatchProtocolMessage(content::DevToolsAgentHost* agent_host,
                               base::span<const uint8_t> message) override {
    std::string raw(reinterpret_cast<const char*>(message.data()),
                    message.size());
    std::optional<base::Value> parsed = base::JSONReader::Read(raw);
    if (!parsed || !parsed->is_dict()) {
      return;
    }
    base::Value::Dict& dict = parsed->GetDict();
    std::optional<int> id = dict.FindInt("id");
    if (!id) {
      const std::string* method = dict.FindString("method");
      if (!method) {
        return;
      }
      std::string params = "{}";
      if (base::Value::Dict* p = dict.FindDict("params")) {
        base::JSONWriter::Write(*p, &params);
      }
      base::Value::Dict ev;
      ev.Set("method", *method);
      ev.Set("tab", tab_id_);
      if (base::Value::Dict* p = dict.FindDict("params")) {
        ev.Set("params", p->Clone());
      }
      std::string ev_json;
      base::JSONWriter::Write(ev, &ev_json);
      LemurXDispatchCdpEvent(*method, ev_json);
      return;
    }
    auto it = waiters_.find(*id);
    if (it == waiters_.end()) {
      return;
    }
    scoped_refptr<Pending> pending = it->second;
    waiters_.erase(it);
    dict.Set("ok", dict.Find("error") == nullptr);
    std::string out;
    base::JSONWriter::Write(dict, &out);
    pending->json = std::move(out);
    pending->event.Signal();
  }

  void AgentHostClosed(content::DevToolsAgentHost* agent_host) override {
    FailAll("{\"id\":0,\"error\":{\"message\":\"target closed\"},\"ok\":false}");
    host_.reset();
    attached_ = false;
    enabled_domains_.clear();
  }

  bool AllowUnsafeOperations() override { return true; }

  std::string GetTypeForMetrics() override { return "LemurX"; }

 private:
  void EnableDefaults() {
    enabled_domains_.clear();
    // 默认只开 Runtime/Page，其余域按需在 MaybeEnable 里首次调用时 enable，
    // 避免一 attach 就把 DOM/CSS/Network 全拉起来拖慢页面。
    Fire("Runtime.enable", "{}");
    enabled_domains_.insert("Runtime");
    const bool page =
        host_ && host_->GetType() == content::DevToolsAgentHost::kTypePage;
    if (page) {
      Fire("Page.enable", "{}");
      enabled_domains_.insert("Page");
    }
  }

  void Fire(const char* method, const char* params) {
    if (!host_) {
      return;
    }
    int id = ++next_id_;
    base::Value::Dict msg;
    msg.Set("id", id);
    msg.Set("method", method);
    std::optional<base::Value> parsed = base::JSONReader::Read(params);
    if (parsed) {
      msg.Set("params", std::move(*parsed));
    }
    std::string json;
    base::JSONWriter::Write(msg, &json);
    host_->DispatchProtocolMessage(this, base::as_bytes(base::make_span(json)));
  }

  void MaybeEnable(const std::string& method) {
    auto dot = method.find('.');
    if (dot == std::string::npos) {
      return;
    }
    std::string domain = method.substr(0, dot);
    if (!enabled_domains_.insert(domain).second) {
      return;
    }
    // 这些域没有 enable 方法（或 enable 需要参数），不要盲发。
    if (domain == "Browser" || domain == "Target" || domain == "IO" ||
        domain == "Emulation" || domain == "Input" || domain == "Memory" ||
        domain == "Tracing" || domain == "SystemInfo" || domain == "Storage" ||
        domain == "Schema") {
      return;
    }
    std::string enable = domain + ".enable";
    Fire(enable.c_str(), "{}");
  }

  void FailAll(const std::string& json) {
    for (auto& item : waiters_) {
      item.second->json = json;
      item.second->event.Signal();
    }
    waiters_.clear();
  }

  const int tab_id_;
  int next_id_ = 1;
  bool attached_ = false;
  scoped_refptr<content::DevToolsAgentHost> host_;
  std::map<int, scoped_refptr<Pending>> waiters_;
  std::set<std::string> enabled_domains_;
};

std::map<int, std::unique_ptr<LemurXCdpClient>>& Clients() {
  static base::NoDestructor<std::map<int, std::unique_ptr<LemurXCdpClient>>>
      clients;
  return *clients;
}

std::map<std::string, std::unique_ptr<LemurXCdpClient>>& HostClients() {
  static base::NoDestructor<
      std::map<std::string, std::unique_ptr<LemurXCdpClient>>>
      hosts;
  return *hosts;
}

void SplitSessionId(std::string* params, std::string* session_id) {
  session_id->clear();
  if (!params || params->empty() || *params == "{}" || *params == "null") {
    return;
  }
  std::optional<base::Value> parsed = base::JSONReader::Read(*params);
  if (!parsed || !parsed->is_dict()) {
    return;
  }
  const std::string* sid = parsed->GetDict().FindString("sessionId");
  if (!sid || sid->empty()) {
    return;
  }
  *session_id = *sid;
  parsed->GetDict().Remove("sessionId");
  std::string out;
  base::JSONWriter::Write(*parsed, &out);
  *params = out.empty() ? "{}" : out;
}

LemurXCdpClient* ClientOnHost(const std::string& host_id, bool create) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto& hosts = HostClients();
  auto it = hosts.find(host_id);
  if (it != hosts.end()) {
    return it->second.get();
  }
  if (!create) {
    return nullptr;
  }
  auto client = std::make_unique<LemurXCdpClient>(-1);
  LemurXCdpClient* raw = client.get();
  hosts[host_id] = std::move(client);
  return raw;
}

scoped_refptr<content::DevToolsAgentHost> HostForId(const std::string& host_id) {
  if (host_id.empty() || host_id == "browser" || host_id == "discovery") {
    return content::DevToolsAgentHost::CreateForDiscovery();
  }
  return content::DevToolsAgentHost::GetForId(host_id);
}

content::WebContents* WebContentsForTabOnUi(int tab_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  return LemurXWebContentsForTab(tab_id);
}

LemurXCdpClient* ClientOnUi(int tab_id, bool create) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto& clients = Clients();
  auto it = clients.find(tab_id);
  if (it != clients.end()) {
    return it->second.get();
  }
  if (!create) {
    return nullptr;
  }
  auto client = std::make_unique<LemurXCdpClient>(tab_id);
  LemurXCdpClient* raw = client.get();
  clients[tab_id] = std::move(client);
  return raw;
}

std::string WaitOrTimeout(scoped_refptr<Pending> pending, int timeout_ms) {
  if (timeout_ms < 500) {
    timeout_ms = 500;
  }
  if (timeout_ms > 120000) {
    timeout_ms = 120000;
  }
  if (!pending->event.TimedWait(base::Milliseconds(timeout_ms))) {
    return "{\"ok\":false,\"error\":\"cdp timeout\"}";
  }
  return pending->json.empty() ? "{\"ok\":false,\"error\":\"empty\"}"
                               : pending->json;
}

}  // namespace

std::string LemurXCdpSend(int tab_id,
                            const std::string& method,
                            const std::string& params_json,
                            int timeout_ms) {
  auto pending = base::MakeRefCounted<Pending>();
  std::string params = params_json;
  std::string session_id;
  SplitSessionId(&params, &session_id);
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](int tab_id, std::string method, std::string params,
             std::string session_id, scoped_refptr<Pending> pending) {
            LemurXCdpClient* client = ClientOnUi(tab_id, true);
            content::WebContents* wc = WebContentsForTabOnUi(tab_id);
            if (!wc) {
              pending->json = "{\"ok\":false,\"error\":\"no webContents\"}";
              pending->event.Signal();
              return;
            }
            if (!client->Attach(wc)) {
              pending->json = "{\"ok\":false,\"error\":\"cdp attach failed\"}";
              pending->event.Signal();
              return;
            }
            client->Send(method, params, session_id, pending);
          },
          tab_id, method, params, session_id, pending));
  return WaitOrTimeout(pending, timeout_ms);
}

std::string LemurXCdpSendHost(const std::string& host_id,
                                const std::string& method,
                                const std::string& params_json,
                                int timeout_ms) {
  auto pending = base::MakeRefCounted<Pending>();
  std::string params = params_json;
  std::string session_id;
  SplitSessionId(&params, &session_id);
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](std::string host_id, std::string method, std::string params,
             std::string session_id, scoped_refptr<Pending> pending) {
            LemurXCdpClient* client = ClientOnHost(host_id, true);
            if (!client->HasHost()) {
              scoped_refptr<content::DevToolsAgentHost> host =
                  HostForId(host_id);
              if (!host || !client->AttachHost(host)) {
                pending->json =
                    "{\"ok\":false,\"error\":\"cdp host attach failed\"}";
                pending->event.Signal();
                return;
              }
            }
            client->Send(method, params, session_id, pending);
          },
          host_id, method, params, session_id, pending));
  return WaitOrTimeout(pending, timeout_ms);
}

std::string LemurXCdpTargets() {
  auto pending = base::MakeRefCounted<Pending>();
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce([](scoped_refptr<Pending> pending) {
        base::Value::Dict root;
        base::Value::List list;
        for (const auto& host : content::DevToolsAgentHost::GetOrCreateAll()) {
          if (!host) {
            continue;
          }
          base::Value::Dict item;
          item.Set("id", host->GetId());
          item.Set("type", host->GetType());
          item.Set("title", host->GetTitle());
          item.Set("url", host->GetURL().spec());
          item.Set("description", host->GetDescription());
          item.Set("attached", host->IsAttached());
          item.Set("parentId", host->GetParentId());
          item.Set("openerId", host->GetOpenerId());
          list.Append(std::move(item));
        }
        root.Set("ok", true);
        root.Set("protocol", content::DevToolsAgentHost::GetProtocolVersion());
        root.Set("targets", std::move(list));
        std::string json;
        base::JSONWriter::Write(root, &json);
        pending->json = json.empty() ? "{\"ok\":false}" : json;
        pending->event.Signal();
      },
      pending));
  return WaitOrTimeout(pending, 8000);
}

bool LemurXCdpAttach(int tab_id) {
  auto pending = base::MakeRefCounted<Pending>();
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(
                     [](int tab_id, scoped_refptr<Pending> pending) {
                       LemurXCdpClient* client = ClientOnUi(tab_id, true);
                       content::WebContents* wc = WebContentsForTabOnUi(tab_id);
                       bool ok = wc && client->Attach(wc);
                       pending->json = ok ? "{\"ok\":true}"
                                          : "{\"ok\":false,\"error\":\"attach\"}";
                       pending->event.Signal();
                     },
                     tab_id, pending));
  std::string json = WaitOrTimeout(pending, 8000);
  return json.find("\"ok\":true") != std::string::npos;
}

void LemurXCdpDetach(int tab_id) {
  base::WaitableEvent event(base::WaitableEvent::ResetPolicy::MANUAL,
                            base::WaitableEvent::InitialState::NOT_SIGNALED);
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(
                     [](int tab_id, base::WaitableEvent* event) {
                       auto& clients = Clients();
                       auto it = clients.find(tab_id);
                       if (it != clients.end()) {
                         it->second->Detach();
                         clients.erase(it);
                       }
                       event->Signal();
                     },
                     tab_id, &event));
  event.TimedWait(base::Seconds(2));
}

std::string LemurXCdpVersion() {
  return content::DevToolsAgentHost::GetProtocolVersion();
}

bool LemurXCdpInspect(int tab_id, int x, int y) {
  auto pending = base::MakeRefCounted<Pending>();
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(
                     [](int tab_id, int x, int y, scoped_refptr<Pending> pending) {
                       LemurXCdpClient* client = ClientOnUi(tab_id, true);
                       content::WebContents* wc = WebContentsForTabOnUi(tab_id);
                       bool ok = wc && client->Attach(wc) && client->Inspect(x, y);
                       pending->json = ok ? "{\"ok\":true}" : "{\"ok\":false}";
                       pending->event.Signal();
                     },
                     tab_id, x, y, pending));
  std::string json = WaitOrTimeout(pending, 5000);
  return json.find("\"ok\":true") != std::string::npos;
}
