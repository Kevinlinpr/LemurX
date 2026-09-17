// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_luakit_scheme.h"

#include <map>
#include <memory>
#include <set>
#include <string>
#include <utility>

#include "base/byte_size.h"
#include "base/functional/bind.h"
#include "base/memory/self_deleting.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/string_util.h"
#include "base/strings/stringprintf.h"
#include "base/synchronization/lock.h"
#include "base/task/sequenced_task_runner.h"
#include "base/time/time.h"
#include "base/timer/timer.h"
#include "base/values.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_native.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_webview.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/child_process_security_policy.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "mojo/public/cpp/system/data_pipe.h"
#include "mojo/public/cpp/system/data_pipe_producer.h"
#include "mojo/public/cpp/system/string_data_source.h"
#include "net/base/net_errors.h"
#include "net/http/http_response_headers.h"
#include "net/http/http_status_code.h"
#include "net/http/http_util.h"
#include "services/network/public/cpp/http_request_headers_update_params.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/self_deleting_url_loader_factory.h"
#include "services/network/public/cpp/url_loader_completion_status.h"
#include "services/network/public/mojom/url_loader.mojom.h"
#include "services/network/public/mojom/url_response_head.mojom.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"
#include "url/gurl.h"

namespace {

// ===== 已注册 scheme 表（任意线程读，Lua 线程写） =====

struct Registry {
  base::Lock lock;
  std::set<std::string> schemes;
};

Registry& GetRegistry() {
  static base::NoDestructor<Registry> r;
  return *r;
}

// Chromium 自己已经处理的 scheme：Lua 注册它们时原生层不接管，
// 交给内建实现（view-source: 尤其如此，luakit 的 view_source.lua 与内建效果等价）。
bool IsBuiltinScheme(const std::string& s) {
  static const char* const kBuiltin[] = {
      "http",  "https",  "ws",         "wss",   "file",       "data",
      "blob",  "about",  "javascript", "chrome", "chrome-untrusted",
      "devtools", "view-source", "filesystem", "content", "intent",
      "chrome-native", "chrome-error", "chrome-extension",
  };
  for (const char* b : kBuiltin) {
    if (s == b) {
      return true;
    }
  }
  return false;
}

void RegisterWebSafeOnUi(const std::string& scheme) {
  content::ChildProcessSecurityPolicy::GetInstance()->RegisterWebSafeScheme(
      scheme);
}

// ===== 单个请求：URLLoader 实现 =====
//
// 生命周期：CreateLoaderAndStart 创建 → 投给 Lua → Lua 回 scheme_reply /
// scheme_error → 写响应 → 自毁。renderer 端断开（页面关掉）也自毁并从表中摘除。

class SchemeLoader;

std::map<int, SchemeLoader*>& Pending() {
  static base::NoDestructor<std::map<int, SchemeLoader*>> m;
  return *m;
}

int NextRequestId() {
  static int next = 1;
  return next++;
}

constexpr base::TimeDelta kLuaReplyTimeout = base::Seconds(30);

class SchemeLoader : public network::mojom::URLLoader {
 public:
  SchemeLoader(mojo::PendingReceiver<network::mojom::URLLoader> receiver,
               mojo::PendingRemote<network::mojom::URLLoaderClient> client,
               const network::ResourceRequest& request,
               int tab_id)
      : id_(NextRequestId()),
        url_(request.url),
        receiver_(this, std::move(receiver)),
        client_(std::move(client)) {
    Pending()[id_] = this;
    receiver_.set_disconnect_handler(
        base::BindOnce(&SchemeLoader::Abort, base::Unretained(this)));
    client_.set_disconnect_handler(
        base::BindOnce(&SchemeLoader::Abort, base::Unretained(this)));
    timeout_.Start(FROM_HERE, kLuaReplyTimeout,
                   base::BindOnce(&SchemeLoader::Fail, base::Unretained(this),
                                  net::ERR_TIMED_OUT));

    base::DictValue d;
    d.Set("ev", "request");
    d.Set("id", id_);
    d.Set("uri", url_.possibly_invalid_spec());
    d.Set("scheme", url_.scheme());
    d.Set("method", request.method);
    d.Set("tab", tab_id);
    d.Set("main_frame", request.is_outermost_main_frame);
    if (request.request_initiator) {
      d.Set("initiator", request.request_initiator->Serialize());
    }
    std::string json;
    base::JSONWriter::Write(d, &json);
    LemurXLuakitDispatch("scheme", id_, json, tab_id);
  }

  SchemeLoader(const SchemeLoader&) = delete;
  SchemeLoader& operator=(const SchemeLoader&) = delete;

  // Lua 回复：data + mime（可带 "; charset=…"）
  void Reply(std::string data, std::string mime, int status_code) {
    timeout_.Stop();
    if (mime.empty()) {
      mime = "text/html";
    }
    std::string mime_only = mime;
    std::string charset;
    size_t semi = mime.find(';');
    if (semi != std::string::npos) {
      mime_only = base::TrimWhitespaceASCII(mime.substr(0, semi),
                                            base::TRIM_ALL);
      std::string rest = mime.substr(semi + 1);
      size_t cs = base::ToLowerASCII(rest).find("charset=");
      if (cs != std::string::npos) {
        charset = base::TrimWhitespaceASCII(rest.substr(cs + 8),
                                            base::TRIM_ALL);
      }
    }
    if (charset.empty() &&
        (base::StartsWith(mime_only, "text/") ||
         mime_only == "application/javascript" ||
         mime_only == "application/json")) {
      charset = "utf-8";
      mime = mime_only + "; charset=utf-8";
    }

    auto head = network::mojom::URLResponseHead::New();
    // 154: GetHttpReasonPhrase 返回 string_view，未知状态码回落 "OK"。
    std::string reason(net::GetHttpReasonPhrase(
        static_cast<net::HttpStatusCode>(status_code), "OK"));
    std::string raw = base::StringPrintf(
        "HTTP/1.1 %d %s\n"
        "Content-Type: %s\n"
        "Content-Length: %zu\n"
        "Cache-Control: no-store\n"
        "X-Content-Type-Options: nosniff\n"
        "Access-Control-Allow-Origin: *\n",
        status_code, reason.c_str(), mime.c_str(), data.size());
    head->headers = base::MakeRefCounted<net::HttpResponseHeaders>(
        net::HttpUtil::AssembleRawHeaders(raw));
    head->mime_type = mime_only;
    head->charset = charset;
    head->content_length = static_cast<int64_t>(data.size());

    mojo::ScopedDataPipeProducerHandle producer;
    mojo::ScopedDataPipeConsumerHandle consumer;
    MojoCreateDataPipeOptions options;
    options.struct_size = sizeof(MojoCreateDataPipeOptions);
    options.flags = MOJO_CREATE_DATA_PIPE_FLAG_NONE;
    options.element_num_bytes = 1;
    options.capacity_num_bytes = 64 * 1024;
    if (mojo::CreateDataPipe(&options, producer, consumer) != MOJO_RESULT_OK) {
      Fail(net::ERR_INSUFFICIENT_RESOURCES);
      return;
    }

    body_size_ = data.size();
    client_->OnReceiveResponse(std::move(head), std::move(consumer),
                               std::nullopt);

    producer_ = std::make_unique<mojo::DataPipeProducer>(std::move(producer));
    producer_->Write(
        std::make_unique<mojo::StringDataSource>(
            std::move(data),
            mojo::StringDataSource::AsyncWritingMode::
                STRING_STAYS_VALID_UNTIL_COMPLETION),
        base::BindOnce(&SchemeLoader::OnBodyWritten, base::Unretained(this)));
  }

  void Fail(int net_error) {
    timeout_.Stop();
    if (client_.is_bound()) {
      client_->OnComplete(network::URLLoaderCompletionStatus(net_error));
    }
    Destroy();
  }

  // network::mojom::URLLoader:
  void FollowRedirect(
      network::HttpRequestHeadersUpdateParams headers_update_params,
      const std::optional<GURL>& new_url) override {}
  void SetPriority(net::RequestPriority priority,
                   int32_t intra_priority_value) override {}

 private:
  ~SchemeLoader() override { Pending().erase(id_); }

  void Abort() {
    // 对端先走了：通知 Lua 这个请求作废（request.finished 不再回投）
    LemurXLuakitDispatch("scheme", id_, "{\"ev\":\"cancel\"}", 0);
    Destroy();
  }

  void OnBodyWritten(MojoResult result) {
    producer_.reset();
    network::URLLoaderCompletionStatus status(
        result == MOJO_RESULT_OK ? net::OK : net::ERR_FAILED);
    // 154: 长度字段是 base::ByteSize 强类型
    status.encoded_data_length = base::ByteSize(body_size_);
    status.encoded_body_length = base::ByteSize(body_size_);
    status.decoded_body_length = base::ByteSize(body_size_);
    client_->OnComplete(status);
    Destroy();
  }

  void Destroy() { delete this; }

  const int id_;
  const GURL url_;
  mojo::Receiver<network::mojom::URLLoader> receiver_;
  mojo::Remote<network::mojom::URLLoaderClient> client_;
  std::unique_ptr<mojo::DataPipeProducer> producer_;
  size_t body_size_ = 0;
  base::OneShotTimer timeout_;
};

// ===== 工厂 =====

class SchemeURLLoaderFactory : public network::SelfDeletingURLLoaderFactory {
 public:
  static mojo::PendingRemote<network::mojom::URLLoaderFactory> Create(
      int tab_id) {
    mojo::PendingRemote<network::mojom::URLLoaderFactory> remote;
    base::MakeSelfDeleting<SchemeURLLoaderFactory>(
        tab_id, remote.InitWithNewPipeAndPassReceiver());
    return remote;
  }

  SchemeURLLoaderFactory(const SchemeURLLoaderFactory&) = delete;
  SchemeURLLoaderFactory& operator=(const SchemeURLLoaderFactory&) = delete;

 private:
  friend class base::internal::MakeSelfDeletingImpl;
  SchemeURLLoaderFactory(
      int tab_id,
      mojo::PendingReceiver<network::mojom::URLLoaderFactory> receiver,
      base::SelfDeletingPassKey key)
      : network::SelfDeletingURLLoaderFactory(std::move(receiver), key),
        tab_id_(tab_id) {}
  ~SchemeURLLoaderFactory() override = default;

  void CreateLoaderAndStart(
      mojo::PendingReceiver<network::mojom::URLLoader> loader,
      int32_t request_id,
      uint32_t options,
      const network::ResourceRequest& request,
      mojo::PendingRemote<network::mojom::URLLoaderClient> client,
      const net::MutableNetworkTrafficAnnotationTag& traffic_annotation)
      override {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!LemurXLuakitIsSchemeRegistered(request.url.scheme())) {
      mojo::Remote<network::mojom::URLLoaderClient> c(std::move(client));
      c->OnComplete(network::URLLoaderCompletionStatus(net::ERR_UNKNOWN_URL_SCHEME));
      return;
    }
    // 自持有：完成 / 断开时自删
    new SchemeLoader(std::move(loader), std::move(client), request, tab_id_);
  }

  const int tab_id_;
};

// ===== Lua 绑定（Lua 线程） =====

void ReplyOnUi(int id, std::string data, std::string mime, int status) {
  auto it = Pending().find(id);
  if (it == Pending().end()) {
    return;
  }
  it->second->Reply(std::move(data), std::move(mime), status);
}

void FailOnUi(int id, int net_error) {
  auto it = Pending().find(id);
  if (it == Pending().end()) {
    return;
  }
  it->second->Fail(net_error);
}

// __luakit.scheme_register(name) -> true | false, err
int SchemeRegister(lua_State* L) {
  std::string scheme = base::ToLowerASCII(luaL_checkstring(L, 1));
  if (scheme.empty()) {
    lua_pushboolean(L, false);
    lua_pushstring(L, "empty scheme");
    return 2;
  }
  for (char c : scheme) {
    if (!(base::IsAsciiAlpha(c) || base::IsAsciiDigit(c) || c == '+' ||
          c == '-' || c == '.')) {
      lua_pushboolean(L, false);
      lua_pushstring(L, "invalid scheme name");
      return 2;
    }
  }
  if (IsBuiltinScheme(scheme)) {
    // 由 Chromium 内建处理；Lua 侧仍可挂信号，但不会收到请求
    lua_pushboolean(L, false);
    lua_pushstring(L, "builtin scheme");
    return 2;
  }
  {
    base::AutoLock lock(GetRegistry().lock);
    GetRegistry().schemes.insert(scheme);
  }
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(&RegisterWebSafeOnUi, scheme));
  lua_pushboolean(L, true);
  return 1;
}

// __luakit.scheme_reply(id, data, mime [, status])
int SchemeReply(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  size_t len = 0;
  const char* p = luaL_checklstring(L, 2, &len);
  std::string data(p, len);
  std::string mime = luaL_optstring(L, 3, "text/html");
  int status = static_cast<int>(luaL_optinteger(L, 4, 200));
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(&ReplyOnUi, id, std::move(data), std::move(mime), status));
  return 0;
}

// __luakit.scheme_error(id [, net_error])
int SchemeError(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  int err = static_cast<int>(luaL_optinteger(L, 2, net::ERR_FILE_NOT_FOUND));
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(&FailOnUi, id, err));
  return 0;
}

// __luakit.scheme_list() -> { "luakit", "gopher", ... }
int SchemeList(lua_State* L) {
  lua_newtable(L);
  base::AutoLock lock(GetRegistry().lock);
  lua_Integer i = 1;
  for (const std::string& s : GetRegistry().schemes) {
    lua_pushstring(L, s.c_str());
    lua_rawseti(L, -2, i++);
  }
  return 1;
}

void SetFn(lua_State* L, const char* name, lua_CFunction fn) {
  lua_pushcfunction(L, fn);
  lua_setfield(L, -2, name);
}

}  // namespace

bool LemurXLuakitIsSchemeRegistered(std::string_view scheme) {
  base::AutoLock lock(GetRegistry().lock);
  return GetRegistry().schemes.count(std::string(scheme)) > 0;
}

mojo::PendingRemote<network::mojom::URLLoaderFactory>
LemurXLuakitMaybeCreateNavigationFactory(
    const std::string& scheme,
    content::FrameTreeNodeId frame_tree_node_id) {
  if (!LemurXLuakitIsSchemeRegistered(scheme)) {
    return {};
  }
  int tab_id = -1;
  content::WebContents* wc =
      content::WebContents::FromFrameTreeNodeId(frame_tree_node_id);
  if (wc) {
    tab_id = LemurXLuakitTabIdForWebContents(wc);
  }
  return SchemeURLLoaderFactory::Create(tab_id);
}

void LemurXLuakitRegisterSubresourceFactories(
    int render_process_id,
    int render_frame_id,
    content::ContentBrowserClient::NonNetworkURLLoaderFactoryMap* factories) {
  std::set<std::string> schemes;
  {
    base::AutoLock lock(GetRegistry().lock);
    schemes = GetRegistry().schemes;
  }
  if (schemes.empty()) {
    return;
  }
  int tab_id = -1;
  content::RenderFrameHost* rfh =
      content::RenderFrameHost::FromID(render_process_id, render_frame_id);
  if (rfh) {
    content::WebContents* wc = content::WebContents::FromRenderFrameHost(rfh);
    if (wc) {
      tab_id = LemurXLuakitTabIdForWebContents(wc);
    }
  }
  for (const std::string& s : schemes) {
    if (factories->count(s)) {
      continue;
    }
    factories->emplace(s, SchemeURLLoaderFactory::Create(tab_id));
  }
}

void RegisterLemurXLuakitScheme(lua_State* L) {
  lua_getglobal(L, "__luakit");
  if (!lua_istable(L, -1)) {
    lua_pop(L, 1);
    return;
  }
  SetFn(L, "scheme_register", SchemeRegister);
  SetFn(L, "scheme_reply", SchemeReply);
  SetFn(L, "scheme_error", SchemeError);
  SetFn(L, "scheme_list", SchemeList);
  lua_pop(L, 1);
}
