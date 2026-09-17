// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/renderer/lemurx/luakit_web_extension.h"

#include <algorithm>
#include <cmath>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/no_destructor.h"
#include "base/strings/string_number_conversions.h"
#include "base/values.h"
#include "content/public/renderer/render_frame.h"
#include "content/public/renderer/render_frame_observer.h"
#include "content/public/renderer/render_thread.h"
#include "net/base/net_errors.h"
#include "net/http/http_request_headers.h"
#include "services/network/public/cpp/resource_request.h"
#include "third_party/blink/public/common/loader/url_loader_throttle.h"
#include "third_party/blink/public/common/tokens/tokens.h"
#include "third_party/blink/public/platform/scheduler/web_agent_group_scheduler.h"
#include "third_party/blink/public/web/web_document.h"
#include "third_party/blink/public/web/web_local_frame.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"
#include "third_party/lua/src/lualib.h"
#include "url/gurl.h"
#include "v8/include/v8-array-buffer.h"
#include "v8/include/v8-container.h"
#include "v8/include/v8-context.h"
#include "v8/include/v8-exception.h"
#include "v8/include/v8-external.h"
#include "v8/include/v8-function.h"
#include "v8/include/v8-isolate.h"
#include "v8/include/v8-local-handle.h"
#include "v8/include/v8-object.h"
#include "v8/include/v8-primitive.h"
#include "v8/include/v8-promise.h"
#include "v8/include/v8-script.h"
#include "v8/include/v8-template.h"
#include "v8/include/v8-value.h"

namespace lemurx {

namespace {

constexpr char kHiddenTable[] = "__luakit_web";
constexpr char kDispatchFn[] = "__luakit_web_dispatch";
constexpr char kHandleKey[] = "__jsh";
constexpr char kKindKey[] = "__jskind";
constexpr int kMaxDepth = 12;

LuakitWebExtension* g_instance = nullptr;

// ===== V8 小工具 =====

v8::Local<v8::String> V8Str(v8::Isolate* isolate, const std::string& s) {
  return v8::String::NewFromUtf8(isolate, s.data(),
                                 v8::NewStringType::kNormal,
                                 static_cast<int>(s.size()))
      .ToLocalChecked();
}

std::string ToStd(v8::Isolate* isolate, v8::Local<v8::Value> v) {
  if (v.IsEmpty()) {
    return std::string();
  }
  v8::String::Utf8Value u(isolate, v);
  return u.length() > 0 ? std::string(*u, u.length()) : std::string();
}

std::string ExceptionToString(v8::Isolate* isolate,
                              v8::Local<v8::Context> ctx,
                              v8::TryCatch& tc) {
  std::string out = ToStd(isolate, tc.Exception());
  v8::Local<v8::Message> m = tc.Message();
  if (!m.IsEmpty()) {
    int line = m->GetLineNumber(ctx).FromMaybe(0);
    std::string res = ToStd(isolate, m->GetScriptResourceName());
    if (!res.empty()) {
      out = res + ":" + base::NumberToString(line) + ": " + out;
    }
  }
  return out;
}

// 绑到 V8 函数上的数据：一个 int id 指向这张表
struct Bound {
  enum Kind { kListener, kLuaFunction, kExposed } kind;
  int routing_id = 0;
  int cb_id = 0;
  std::string name;   // kExposed: window[name]
};

std::map<int, Bound>& BoundTable() {
  static base::NoDestructor<std::map<int, Bound>> m;
  return *m;
}

int NextBoundId() {
  static int next = 1;
  return next++;
}

// ===== Lua 侧辅助 =====

// {__jsh = id, __jskind = kind}
void PushHandleTable(lua_State* L, int handle, const char* kind) {
  lua_createtable(L, 0, 2);
  lua_pushinteger(L, handle);
  lua_setfield(L, -2, kHandleKey);
  lua_pushstring(L, kind);
  lua_setfield(L, -2, kKindKey);
}

// 栈上 idx 是不是句柄表；是则返回 id
int HandleAt(lua_State* L, int idx) {
  if (!lua_istable(L, idx)) {
    return 0;
  }
  lua_getfield(L, idx, kHandleKey);
  int h = lua_isinteger(L, -1) ? static_cast<int>(lua_tointeger(L, -1)) : 0;
  lua_pop(L, 1);
  return h;
}

int PushNilErr(lua_State* L, const std::string& err) {
  lua_pushnil(L);
  lua_pushlstring(L, err.data(), err.size());
  return 2;
}

// base::Value ↔ Lua（JSON 参数用）
void PushValue(lua_State* L, const base::Value& v) {
  switch (v.type()) {
    case base::Value::Type::BOOLEAN:
      lua_pushboolean(L, v.GetBool());
      break;
    case base::Value::Type::INTEGER:
      lua_pushinteger(L, v.GetInt());
      break;
    case base::Value::Type::DOUBLE:
      lua_pushnumber(L, v.GetDouble());
      break;
    case base::Value::Type::STRING:
      lua_pushlstring(L, v.GetString().data(), v.GetString().size());
      break;
    case base::Value::Type::DICT:
      lua_newtable(L);
      for (const auto item : v.GetDict()) {
        PushValue(L, item.second);
        lua_setfield(L, -2, item.first.c_str());
      }
      break;
    case base::Value::Type::LIST: {
      lua_newtable(L);
      lua_Integer i = 1;
      for (const auto& child : v.GetList()) {
        PushValue(L, child);
        lua_rawseti(L, -2, i++);
      }
      break;
    }
    default:
      lua_pushnil(L);
      break;
  }
}

base::Value LuaToValue(lua_State* L, int idx, int depth) {
  idx = lua_absindex(L, idx);
  switch (lua_type(L, idx)) {
    case LUA_TBOOLEAN:
      return base::Value(lua_toboolean(L, idx) != 0);
    case LUA_TNUMBER:
      if (lua_isinteger(L, idx)) {
        lua_Integer i = lua_tointeger(L, idx);
        if (i >= INT32_MIN && i <= INT32_MAX) {
          return base::Value(static_cast<int>(i));
        }
        return base::Value(static_cast<double>(i));
      }
      return base::Value(lua_tonumber(L, idx));
    case LUA_TSTRING: {
      size_t len = 0;
      const char* s = lua_tolstring(L, idx, &len);
      return base::Value(std::string(s, len));
    }
    case LUA_TTABLE: {
      if (depth > kMaxDepth) {
        return base::Value();
      }
      lua_Integer n = static_cast<lua_Integer>(lua_rawlen(L, idx));
      bool is_array = n > 0;
      if (is_array) {
        lua_pushnil(L);
        while (lua_next(L, idx)) {
          if (!lua_isinteger(L, -2) || lua_tointeger(L, -2) < 1 ||
              lua_tointeger(L, -2) > n) {
            is_array = false;
            lua_pop(L, 2);
            break;
          }
          lua_pop(L, 1);
        }
      }
      if (is_array) {
        base::Value::List list;
        for (lua_Integer i = 1; i <= n; ++i) {
          lua_rawgeti(L, idx, i);
          list.Append(LuaToValue(L, -1, depth + 1));
          lua_pop(L, 1);
        }
        return base::Value(std::move(list));
      }
      base::Value::Dict dict;
      lua_pushnil(L);
      while (lua_next(L, idx)) {
        std::string key;
        if (lua_type(L, -2) == LUA_TSTRING) {
          key = lua_tostring(L, -2);
        } else if (lua_isnumber(L, -2)) {
          key = base::NumberToString(lua_tonumber(L, -2));
        }
        if (!key.empty()) {
          dict.Set(key, LuaToValue(L, -1, depth + 1));
        }
        lua_pop(L, 1);
      }
      return base::Value(std::move(dict));
    }
    default:
      return base::Value();
  }
}

// ===== 每帧观察者 =====

class FrameObserver : public content::RenderFrameObserver {
 public:
  explicit FrameObserver(content::RenderFrame* frame)
      : content::RenderFrameObserver(frame) {
    if (auto* ext = LuakitWebExtension::Get()) {
      ext->OnFrameReady(frame);
    }
  }

  void DidClearWindowObject() override {
    if (auto* ext = LuakitWebExtension::Get()) {
      ext->OnWindowObjectCleared(routing_id());
    }
  }

  void DidDispatchDOMContentLoadedEvent() override {
    if (auto* ext = LuakitWebExtension::Get()) {
      ext->OnDocumentLoaded(routing_id());
    }
  }

  void OnDestruct() override {
    if (auto* ext = LuakitWebExtension::Get()) {
      ext->OnFrameDestroyed(routing_id());
    }
    delete this;
  }
};

// ===== V8 → Lua 回调（监听器 / Lua 函数包装 / 暴露函数）=====

void BoundCallback(const v8::FunctionCallbackInfo<v8::Value>& info) {
  auto* ext = LuakitWebExtension::Get();
  if (!ext || !ext->L()) {
    return;
  }
  v8::Isolate* isolate = info.GetIsolate();
  int bound_id = info.Data()->Int32Value(isolate->GetCurrentContext()).FromMaybe(0);
  auto it = BoundTable().find(bound_id);
  if (it == BoundTable().end()) {
    return;
  }
  Bound b = it->second;
  lua_State* L = ext->L();
  v8::Local<v8::Context> ctx = isolate->GetCurrentContext();
  int top = lua_gettop(L);

  switch (b.kind) {
    case Bound::kListener: {
      // __luakit_web_dispatch("event", cb_id, event)
      lua_pushinteger(L, b.cb_id);
      if (info.Length() > 0) {
        ext->V8ToLua(L, info[0], ctx, b.routing_id);
      } else {
        lua_pushnil(L);
      }
      ext->Dispatch("event", 2);
      break;
    }
    case Bound::kLuaFunction: {
      // __luakit_web_dispatch("call", cb_id, ...) -> ret
      lua_pushinteger(L, b.cb_id);
      for (int i = 0; i < info.Length(); ++i) {
        ext->V8ToLua(L, info[i], ctx, b.routing_id);
      }
      ext->Dispatch("call", 1 + info.Length());
      if (lua_gettop(L) > top) {
        info.GetReturnValue().Set(ext->LuaToV8(L, top + 1, ctx, b.routing_id));
      }
      break;
    }
    case Bound::kExposed: {
      // luakit.register_function：返回 Promise，Lua 拿 resolve/reject 句柄
      v8::Local<v8::Promise::Resolver> resolver;
      if (!v8::Promise::Resolver::New(ctx).ToLocal(&resolver)) {
        break;
      }
      int rh = ext->RetainValue(b.routing_id, resolver);
      lua_pushinteger(L, b.routing_id);
      lua_pushstring(L, b.name.c_str());
      PushHandleTable(L, rh, "object");
      for (int i = 0; i < info.Length(); ++i) {
        ext->V8ToLua(L, info[i], ctx, b.routing_id);
      }
      ext->Dispatch("fn", 3 + info.Length());
      info.GetReturnValue().Set(resolver->GetPromise());
      break;
    }
  }
  lua_settop(L, top);
}

// ===== 原语：全部在 __luakit_web 表里 =====

LuakitWebExtension* Ext(lua_State* L) {
  auto* ext = LuakitWebExtension::Get();
  if (!ext) {
    luaL_error(L, "luakit web extension not available");
  }
  return ext;
}

// 进入某页面主世界 context：HandleScope + Context::Scope。
// 原语从 Lua 被调时（mojo 消息处理栈上）没有现成的 HandleScope，必须自己建。
struct Scoped {
  Scoped(LuakitWebExtension* ext, int routing_id) {
    isolate = ext->IsolateFor(routing_id);
    if (!isolate) {
      return;
    }
    handle_scope.emplace(isolate);
    ctx = ext->ContextFor(routing_id);
    if (ctx.IsEmpty()) {
      handle_scope.reset();
      isolate = nullptr;
      return;
    }
    scope.emplace(ctx);
  }
  v8::Isolate* isolate = nullptr;
  std::optional<v8::HandleScope> handle_scope;
  v8::Local<v8::Context> ctx;
  std::optional<v8::Context::Scope> scope;
  bool ok() const { return !ctx.IsEmpty(); }
};

// 句柄 → (Scoped, Local)。失败时已 push nil, err 并返回 false。
struct HandleScopeFor {
  HandleScopeFor(lua_State* L, LuakitWebExtension* ext, int handle) {
    rid = ext->RoutingIdForHandle(handle);
    if (rid == 0) {
      nret = PushNilErr(L, "invalid js handle");
      return;
    }
    s.emplace(ext, rid);
    if (!s->ok()) {
      nret = PushNilErr(L, "page destroyed");
      return;
    }
    if (!ext->LookupValue(handle, &value, &rid)) {
      nret = PushNilErr(L, "invalid js handle");
      return;
    }
    ok = true;
  }
  bool ok = false;
  int nret = 0;
  int rid = 0;
  std::optional<Scoped> s;
  v8::Local<v8::Value> value;
};

// env() -> json string
int PEnv(lua_State* L) {
  const std::string& s = Ext(L)->env_json();
  lua_pushlstring(L, s.data(), s.size());
  return 1;
}

// log(level, group, msg)
int PLog(lua_State* L) {
  int level = static_cast<int>(luaL_checkinteger(L, 1));
  std::string group = luaL_optstring(L, 2, "");
  std::string message = luaL_checkstring(L, 3);
  auto* ext = Ext(L);
  if (ext->host()) {
    ext->host()->Log(level, group, message);
  }
  if (level <= 1) {
    LOG(WARNING) << "luakit-web[" << group << "] " << message;
  } else {
    VLOG(1) << "luakit-web[" << group << "] " << message;
  }
  return 0;
}

// ipc_send(channel, signame, args_json)
int PIpcSend(lua_State* L) {
  std::string channel = luaL_checkstring(L, 1);
  std::string signame = luaL_checkstring(L, 2);
  std::string json = luaL_optstring(L, 3, "[]");
  auto* ext = Ext(L);
  if (ext->host()) {
    ext->host()->EmitSignal(channel, signame, json);
  }
  return 0;
}

// eval_js_reply(cb_id, result_json, err|nil)
int PEvalJsReply(lua_State* L) {
  int cb = static_cast<int>(luaL_checkinteger(L, 1));
  std::string json = luaL_optstring(L, 2, "null");
  std::optional<std::string> err;
  if (!lua_isnoneornil(L, 3)) {
    err = luaL_checkstring(L, 3);
  }
  auto* ext = Ext(L);
  if (ext->host()) {
    ext->host()->EvalJsResult(cb, json, err);
  }
  return 0;
}

// json_encode(v) -> string ; json_decode(s) -> v
int PJsonEncode(lua_State* L) {
  base::Value v = LuaToValue(L, 1, 0);
  std::string out;
  base::JSONWriter::Write(v, &out);
  lua_pushlstring(L, out.data(), out.size());
  return 1;
}

int PJsonDecode(lua_State* L) {
  size_t len = 0;
  const char* s = luaL_checklstring(L, 1, &len);
  std::optional<base::Value> v = base::JSONReader::Read(std::string_view(s, len));
  if (!v) {
    lua_pushnil(L);
    return 1;
  }
  PushValue(L, *v);
  return 1;
}

// page_alive(rid) -> bool ; page_uri(rid) -> string|nil ; page_id(rid) -> int
int PPageAlive(lua_State* L) {
  int rid = static_cast<int>(luaL_checkinteger(L, 1));
  auto* p = Ext(L)->PageByRouting(rid);
  lua_pushboolean(L, p && p->frame);
  return 1;
}

int PPageUri(lua_State* L) {
  int rid = static_cast<int>(luaL_checkinteger(L, 1));
  auto* p = Ext(L)->PageByRouting(rid);
  if (!p || !p->frame) {
    lua_pushnil(L);
    return 1;
  }
  GURL url(p->frame->GetWebFrame()->GetDocument().Url());
  std::string s = url.is_valid() ? url.spec() : "about:blank";
  lua_pushlstring(L, s.data(), s.size());
  return 1;
}

// page_global(rid) -> handle(window)
int PPageGlobal(lua_State* L) {
  int rid = static_cast<int>(luaL_checkinteger(L, 1));
  auto* ext = Ext(L);
  Scoped s(ext, rid);
  if (!s.ok()) {
    return PushNilErr(L, "page destroyed");
  }
  int h = ext->RetainValue(rid, s.ctx->Global());
  PushHandleTable(L, h, "object");
  return 1;
}

// page_eval(rid, script, source) -> value | nil, err
int PPageEval(lua_State* L) {
  int rid = static_cast<int>(luaL_checkinteger(L, 1));
  size_t len = 0;
  const char* code = luaL_checklstring(L, 2, &len);
  std::string source = luaL_optstring(L, 3, "(lua)");
  auto* ext = Ext(L);
  Scoped s(ext, rid);
  if (!s.ok()) {
    return PushNilErr(L, "page destroyed");
  }
  v8::TryCatch tc(s.isolate);
  v8::ScriptOrigin origin(V8Str(s.isolate, source));
  v8::Local<v8::Script> script;
  if (!v8::Script::Compile(s.ctx, V8Str(s.isolate, std::string(code, len)),
                           &origin)
           .ToLocal(&script)) {
    return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
  }
  v8::Local<v8::Value> result;
  if (!script->Run(s.ctx).ToLocal(&result)) {
    return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
  }
  ext->V8ToLua(L, result, s.ctx, rid);
  return 1;
}

// js_get(h, key) -> value
int PJsGet(lua_State* L) {
  auto* ext = Ext(L);
  HandleScopeFor hs(L, ext, HandleAt(L, 1));
  if (!hs.ok) {
    return hs.nret;
  }
  Scoped& s = *hs.s;
  if (!hs.value->IsObject()) {
    return PushNilErr(L, "not an object");
  }
  v8::TryCatch tc(s.isolate);
  v8::Local<v8::Value> key;
  if (lua_isinteger(L, 2)) {
    key = v8::Integer::New(s.isolate, static_cast<int32_t>(lua_tointeger(L, 2)));
  } else {
    key = V8Str(s.isolate, luaL_checkstring(L, 2));
  }
  v8::Local<v8::Value> out;
  if (!hs.value.As<v8::Object>()->Get(s.ctx, key).ToLocal(&out)) {
    return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
  }
  ext->V8ToLua(L, out, s.ctx, hs.rid);
  return 1;
}

// js_set(h, key, value) -> true | nil, err
int PJsSet(lua_State* L) {
  auto* ext = Ext(L);
  HandleScopeFor hs(L, ext, HandleAt(L, 1));
  if (!hs.ok) {
    return hs.nret;
  }
  Scoped& s = *hs.s;
  if (!hs.value->IsObject()) {
    return PushNilErr(L, "not an object");
  }
  v8::TryCatch tc(s.isolate);
  v8::Local<v8::Value> key;
  if (lua_isinteger(L, 2)) {
    key = v8::Integer::New(s.isolate, static_cast<int32_t>(lua_tointeger(L, 2)));
  } else {
    key = V8Str(s.isolate, luaL_checkstring(L, 2));
  }
  v8::Local<v8::Value> val = ext->LuaToV8(L, 3, s.ctx, hs.rid);
  if (hs.value.As<v8::Object>()->Set(s.ctx, key, val).IsNothing()) {
    return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
  }
  lua_pushboolean(L, true);
  return 1;
}

// js_call(h, method|nil, ...) -> value | nil, err
//   method 为字符串：h[method](...)，this = h
//   method 为 nil：h(...)，this = undefined
int PJsCall(lua_State* L) {
  auto* ext = Ext(L);
  HandleScopeFor hs(L, ext, HandleAt(L, 1));
  if (!hs.ok) {
    return hs.nret;
  }
  Scoped& s = *hs.s;
  v8::TryCatch tc(s.isolate);
  v8::Local<v8::Value> fn_value;
  v8::Local<v8::Value> recv;
  if (lua_isnoneornil(L, 2)) {
    fn_value = hs.value;
    recv = v8::Undefined(s.isolate);
  } else {
    if (!hs.value->IsObject()) {
      return PushNilErr(L, "not an object");
    }
    std::string method = luaL_checkstring(L, 2);
    if (!hs.value.As<v8::Object>()
             ->Get(s.ctx, V8Str(s.isolate, method))
             .ToLocal(&fn_value)) {
      return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
    }
    recv = hs.value;
  }
  if (!fn_value->IsFunction()) {
    return PushNilErr(L, "not a function");
  }
  int nargs = lua_gettop(L) - 2;
  if (nargs < 0) {
    nargs = 0;
  }
  std::vector<v8::Local<v8::Value>> argv;
  argv.reserve(nargs);
  for (int i = 0; i < nargs; ++i) {
    argv.push_back(ext->LuaToV8(L, 3 + i, s.ctx, hs.rid));
  }
  v8::Local<v8::Value> out;
  if (!fn_value.As<v8::Function>()
           ->Call(s.ctx, recv, nargs, argv.data())
           .ToLocal(&out)) {
    return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
  }
  ext->V8ToLua(L, out, s.ctx, hs.rid);
  return 1;
}

// js_release(h)
int PJsRelease(lua_State* L) {
  int h = HandleAt(L, 1);
  if (h) {
    Ext(L)->ReleaseValue(h);
  }
  return 0;
}

// js_eq(h1, h2) -> bool
int PJsEq(lua_State* L) {
  int a = HandleAt(L, 1);
  int b = HandleAt(L, 2);
  if (a == b) {
    lua_pushboolean(L, a != 0);
    return 1;
  }
  auto* ext = Ext(L);
  int ra = ext->RoutingIdForHandle(a);
  int rb = ext->RoutingIdForHandle(b);
  if (!ra || ra != rb) {
    lua_pushboolean(L, false);
    return 1;
  }
  Scoped s(ext, ra);
  if (!s.ok()) {
    lua_pushboolean(L, false);
    return 1;
  }
  v8::Local<v8::Value> va, vb;
  if (!ext->LookupValue(a, &va, &ra) || !ext->LookupValue(b, &vb, &rb)) {
    lua_pushboolean(L, false);
    return 1;
  }
  lua_pushboolean(L, va->StrictEquals(vb));
  return 1;
}

// js_typeof(h) -> "object"|"function"|... , nodeType(0 表示不是节点)
int PJsTypeof(lua_State* L) {
  auto* ext = Ext(L);
  HandleScopeFor hs(L, ext, HandleAt(L, 1));
  if (!hs.ok) {
    lua_settop(L, 1);
    lua_pushnil(L);
    return 1;
  }
  Scoped& s = *hs.s;
  lua_pushstring(L, ToStd(s.isolate, hs.value->TypeOf(s.isolate)).c_str());
  int node_type = 0;
  if (hs.value->IsObject()) {
    v8::Local<v8::Value> nt;
    if (hs.value.As<v8::Object>()
            ->Get(s.ctx, V8Str(s.isolate, "nodeType"))
            .ToLocal(&nt) &&
        nt->IsNumber()) {
      node_type = nt->Int32Value(s.ctx).FromMaybe(0);
    }
  }
  lua_pushinteger(L, node_type);
  return 2;
}

// js_listen(h, type, capture, cb_id) -> true, bound_id | nil, err
int PJsListen(lua_State* L) {
  std::string type = luaL_checkstring(L, 2);
  bool capture = lua_toboolean(L, 3) != 0;
  int cb_id = static_cast<int>(luaL_checkinteger(L, 4));
  auto* ext = Ext(L);
  HandleScopeFor hs(L, ext, HandleAt(L, 1));
  if (!hs.ok) {
    return hs.nret;
  }
  Scoped& s = *hs.s;
  if (!hs.value->IsObject()) {
    return PushNilErr(L, "not an object");
  }
  int bound_id = NextBoundId();
  BoundTable()[bound_id] = Bound{Bound::kListener, hs.rid, cb_id, type};
  v8::Local<v8::Function> fn;
  if (!v8::Function::New(s.ctx, &BoundCallback,
                         v8::Integer::New(s.isolate, bound_id))
           .ToLocal(&fn)) {
    BoundTable().erase(bound_id);
    return PushNilErr(L, "cannot create listener");
  }
  ext->StoreListener(cb_id, hs.rid, fn);
  v8::TryCatch tc(s.isolate);
  v8::Local<v8::Value> add;
  if (!hs.value.As<v8::Object>()
           ->Get(s.ctx, V8Str(s.isolate, "addEventListener"))
           .ToLocal(&add) ||
      !add->IsFunction()) {
    return PushNilErr(L, "target has no addEventListener");
  }
  v8::Local<v8::Value> argv[] = {V8Str(s.isolate, type), fn,
                                 v8::Boolean::New(s.isolate, capture)};
  if (add.As<v8::Function>()->Call(s.ctx, hs.value, 3, argv).IsEmpty()) {
    return PushNilErr(L, ExceptionToString(s.isolate, s.ctx, tc));
  }
  lua_pushboolean(L, true);
  lua_pushinteger(L, bound_id);
  return 2;
}

// js_unlisten(h, type, capture, cb_id, bound_id)
int PJsUnlisten(lua_State* L) {
  std::string type = luaL_checkstring(L, 2);
  bool capture = lua_toboolean(L, 3) != 0;
  int cb_id = static_cast<int>(luaL_checkinteger(L, 4));
  int bound_id = static_cast<int>(luaL_optinteger(L, 5, 0));
  auto* ext = Ext(L);
  if (bound_id) {
    BoundTable().erase(bound_id);
  }
  HandleScopeFor hs(L, ext, HandleAt(L, 1));
  if (!hs.ok) {
    ext->DropListener(cb_id);
    return 0;
  }
  Scoped& s = *hs.s;
  v8::Local<v8::Function> fn;
  int lrid = 0;
  if (!ext->TakeListener(cb_id, &fn, &lrid) || fn.IsEmpty() ||
      !hs.value->IsObject()) {
    ext->DropListener(cb_id);
    return 0;
  }
  v8::Local<v8::Value> rm;
  if (hs.value.As<v8::Object>()
          ->Get(s.ctx, V8Str(s.isolate, "removeEventListener"))
          .ToLocal(&rm) &&
      rm->IsFunction()) {
    v8::Local<v8::Value> argv[] = {V8Str(s.isolate, type), fn,
                                   v8::Boolean::New(s.isolate, capture)};
    (void)rm.As<v8::Function>()->Call(s.ctx, hs.value, 3, argv);
  }
  ext->DropListener(cb_id);
  return 0;
}

// js_function(rid, cb_id) -> handle：一个调回 Lua 的 JS 函数
int PJsFunction(lua_State* L) {
  int rid = static_cast<int>(luaL_checkinteger(L, 1));
  int cb_id = static_cast<int>(luaL_checkinteger(L, 2));
  auto* ext = Ext(L);
  Scoped s(ext, rid);
  if (!s.ok()) {
    return PushNilErr(L, "page destroyed");
  }
  int bound_id = NextBoundId();
  BoundTable()[bound_id] = Bound{Bound::kLuaFunction, rid, cb_id, ""};
  v8::Local<v8::Function> fn;
  if (!v8::Function::New(s.ctx, &BoundCallback,
                         v8::Integer::New(s.isolate, bound_id))
           .ToLocal(&fn)) {
    BoundTable().erase(bound_id);
    return PushNilErr(L, "cannot create function");
  }
  int h = ext->RetainValue(rid, fn);
  PushHandleTable(L, h, "function");
  return 1;
}

// expose(rid, name)：window[name] = 返回 Promise 的函数（luakit.register_function）
int PExpose(lua_State* L) {
  int rid = static_cast<int>(luaL_checkinteger(L, 1));
  std::string name = luaL_checkstring(L, 2);
  auto* ext = Ext(L);
  Scoped s(ext, rid);
  if (!s.ok()) {
    return PushNilErr(L, "page destroyed");
  }
  int bound_id = NextBoundId();
  BoundTable()[bound_id] = Bound{Bound::kExposed, rid, 0, name};
  v8::Local<v8::Function> fn;
  if (!v8::Function::New(s.ctx, &BoundCallback,
                         v8::Integer::New(s.isolate, bound_id))
           .ToLocal(&fn)) {
    BoundTable().erase(bound_id);
    return PushNilErr(L, "cannot create function");
  }
  fn->SetName(V8Str(s.isolate, name));
  if (s.ctx->Global()->Set(s.ctx, V8Str(s.isolate, name), fn).IsNothing()) {
    return PushNilErr(L, "cannot set window property");
  }
  lua_pushboolean(L, true);
  return 1;
}

// uri_parse(s) -> table|nil
int PUriParse(lua_State* L) {
  GURL url(luaL_checkstring(L, 1));
  if (!url.is_valid()) {
    lua_pushnil(L);
    return 1;
  }
  lua_newtable(L);
  auto set = [&](const char* k, const std::string& v) {
    if (!v.empty()) {
      lua_pushlstring(L, v.data(), v.size());
      lua_setfield(L, -2, k);
    }
  };
  set("scheme", url.scheme());
  set("host", url.host());
  set("path", url.path().empty() ? "/" : url.path());
  set("query", url.query());
  set("fragment", url.ref());
  set("user", url.username());
  set("password", url.password());
  if (url.has_port()) {
    lua_pushinteger(L, url.IntPort());
    lua_setfield(L, -2, "port");
  }
  return 1;
}

void SetFn(lua_State* L, const char* name, lua_CFunction fn) {
  lua_pushcfunction(L, fn);
  lua_setfield(L, -2, name);
}

// package.searchers[2]：向浏览器同步要源码
int ModuleSearcher(lua_State* L) {
  std::string name = luaL_checkstring(L, 1);
  auto* ext = LuakitWebExtension::Get();
  if (!ext || !ext->host()) {
    lua_pushstring(L, "\n\tluakit web host not connected");
    return 1;
  }
  bool ok = false;
  std::string source;
  std::string chunkname;
  if (!ext->host()->ResolveModule(name, &ok, &source, &chunkname) || !ok) {
    lua_pushfstring(L, "\n\tno module '%s' in luakit web search path",
                    name.c_str());
    return 1;
  }
  if (luaL_loadbuffer(L, source.data(), source.size(),
                      ("@" + chunkname).c_str()) != LUA_OK) {
    return luaL_error(L, "error loading module '%s':\n\t%s", name.c_str(),
                      lua_tostring(L, -1));
  }
  lua_pushstring(L, chunkname.c_str());
  return 2;
}

}  // namespace

// ===== LuakitWebExtension =====

// static
LuakitWebExtension* LuakitWebExtension::Get() {
  return g_instance;
}

// static
void LuakitWebExtension::Create() {
  if (g_instance) {
    return;
  }
  g_instance = new LuakitWebExtension();
  content::RenderThread* thread = content::RenderThread::Get();
  if (!thread) {
    return;
  }
  thread->BindHostReceiver(g_instance->host_.BindNewPipeAndPassReceiver());
  g_instance->host_->Ready();
}

// static
void LuakitWebExtension::OnRenderFrameCreated(content::RenderFrame* frame) {
  if (!frame || !frame->IsMainFrame()) {
    return;
  }
  // 自管生命周期：OnDestruct 里 delete
  new FrameObserver(frame);
}

LuakitWebExtension::Handle::Handle() = default;
LuakitWebExtension::Handle::Handle(Handle&&) = default;
LuakitWebExtension::Handle& LuakitWebExtension::Handle::operator=(Handle&&) =
    default;
LuakitWebExtension::Handle::~Handle() = default;

LuakitWebExtension::Listener::Listener() = default;
LuakitWebExtension::Listener::Listener(Listener&&) = default;
LuakitWebExtension::Listener& LuakitWebExtension::Listener::operator=(
    Listener&&) = default;
LuakitWebExtension::Listener::~Listener() = default;

LuakitWebExtension::LuakitWebExtension() = default;

LuakitWebExtension::~LuakitWebExtension() {
  if (L_) {
    lua_close(L_);
  }
}

void LuakitWebExtension::Bind(
    mojo::PendingReceiver<mojom::LuakitWebExtension> receiver) {
  receiver_.reset();
  receiver_.Bind(std::move(receiver));
}

void LuakitWebExtension::EnsureState() {
  if (L_) {
    return;
  }
  L_ = luaL_newstate();
  luaL_openlibs(L_);
  lua_newtable(L_);
  RegisterPrimitives();
  lua_setglobal(L_, kHiddenTable);

  // 搜索器插到 package.searchers[2]（preload 之后）
  lua_getglobal(L_, "package");
  lua_getfield(L_, -1, "searchers");
  if (lua_istable(L_, -1)) {
    lua_Integer n = static_cast<lua_Integer>(lua_rawlen(L_, -1));
    for (lua_Integer i = n; i >= 2; --i) {
      lua_rawgeti(L_, -1, i);
      lua_rawseti(L_, -2, i + 1);
    }
    lua_pushcfunction(L_, ModuleSearcher);
    lua_rawseti(L_, -2, 2);
  }
  lua_pop(L_, 2);
}

void LuakitWebExtension::RegisterPrimitives() {
  lua_State* L = L_;
  SetFn(L, "env", PEnv);
  SetFn(L, "log", PLog);
  SetFn(L, "ipc_send", PIpcSend);
  SetFn(L, "eval_js_reply", PEvalJsReply);
  SetFn(L, "json_encode", PJsonEncode);
  SetFn(L, "json_decode", PJsonDecode);
  SetFn(L, "page_alive", PPageAlive);
  SetFn(L, "page_uri", PPageUri);
  SetFn(L, "page_global", PPageGlobal);
  SetFn(L, "page_eval", PPageEval);
  SetFn(L, "js_get", PJsGet);
  SetFn(L, "js_set", PJsSet);
  SetFn(L, "js_call", PJsCall);
  SetFn(L, "js_release", PJsRelease);
  SetFn(L, "js_eq", PJsEq);
  SetFn(L, "js_typeof", PJsTypeof);
  SetFn(L, "js_listen", PJsListen);
  SetFn(L, "js_unlisten", PJsUnlisten);
  SetFn(L, "js_function", PJsFunction);
  SetFn(L, "expose", PExpose);
  SetFn(L, "uri_parse", PUriParse);
}

void LuakitWebExtension::Dispatch(const char* kind, int nargs) {
  if (!L_ || !inited_) {
    // 调用方随后会 lua_settop 回原位，这里不动栈
    return;
  }
  lua_State* L = L_;
  int base = lua_gettop(L) - nargs;  // 参数起点的前一个位置
  lua_getglobal(L, kDispatchFn);
  if (!lua_isfunction(L, -1)) {
    lua_settop(L, base);
    return;
  }
  lua_insert(L, base + 1);  // fn 放到参数之前
  lua_pushstring(L, kind);
  lua_insert(L, base + 2);  // kind 紧随 fn
  int status = lua_pcall(L, nargs + 1, LUA_MULTRET, 0);
  if (status != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "?";
    LOG(WARNING) << "luakit-web dispatch(" << kind << ") failed: " << err;
    if (host_) {
      host_->Log(1, "web", "dispatch(" + std::string(kind) + "): " + err);
    }
    lua_settop(L, base);
  }
  // 成功：返回值留在栈上（调用方按需读取后 settop）
}

// ---- send-request 节流器 ----

namespace {

class LuakitRequestThrottle : public blink::URLLoaderThrottle {
 public:
  explicit LuakitRequestThrottle(int routing_id) : routing_id_(routing_id) {}
  ~LuakitRequestThrottle() override = default;

  // 请求可能被搬到别的序列（同步 XHR 等）；那时不在主线程，跳过。
  void DetachFromCurrentSequence() override { detached_ = true; }

  void WillStartRequest(network::ResourceRequest* request,
                        bool* defer) override {
    if (detached_ || !content::RenderThread::IsMainThread()) {
      return;
    }
    auto* ext = LuakitWebExtension::Get();
    if (!ext) {
      return;
    }
    if (!ext->OnSendRequest(routing_id_, request)) {
      delegate_->CancelWithError(net::ERR_BLOCKED_BY_CLIENT, "luakit");
    }
  }

 private:
  int routing_id_;
  bool detached_ = false;
};

}  // namespace

// static
std::unique_ptr<blink::URLLoaderThrottle>
LuakitWebExtension::MaybeCreateURLLoaderThrottle(
    const blink::LocalFrameToken& frame_token) {
  auto* ext = Get();
  if (!ext || !ext->inited_) {
    return nullptr;
  }
  blink::WebLocalFrame* frame = blink::WebLocalFrame::FromFrameToken(frame_token);
  if (!frame) {
    return nullptr;
  }
  // luakit 的 send-request 在 WebKitWebPage 级别：子框架的请求也归主框架的 page。
  blink::WebFrame* top = frame->Top();
  blink::WebLocalFrame* top_local = top ? top->ToWebLocalFrame() : nullptr;
  if (!top_local) {
    return nullptr;
  }
  content::RenderFrame* rf = content::RenderFrame::FromWebFrame(top_local);
  if (!rf) {
    return nullptr;
  }
  Page* p = ext->PageByRouting(rf->GetRoutingID());
  if (!p || !p->created_emitted) {
    return nullptr;
  }
  return std::make_unique<LuakitRequestThrottle>(rf->GetRoutingID());
}

bool LuakitWebExtension::OnSendRequest(int routing_id,
                                       network::ResourceRequest* request) {
  Page* p = PageByRouting(routing_id);
  if (!p || !inited_ || !p->created_emitted || !L_) {
    return true;
  }
  lua_State* L = L_;
  int top = lua_gettop(L);
  lua_pushinteger(L, routing_id);
  const std::string spec = request->url.spec();
  lua_pushlstring(L, spec.data(), spec.size());
  // headers 表：请求头 + Referer（Chromium 把 referrer 单独放）
  lua_newtable(L);
  for (const auto& kv : request->headers.GetHeaderVector()) {
    lua_pushlstring(L, kv.value.data(), kv.value.size());
    lua_setfield(L, -2, kv.key.c_str());
  }
  const bool had_referer = !request->referrer.is_empty();
  if (had_referer) {
    const std::string ref = request->referrer.spec();
    lua_pushlstring(L, ref.data(), ref.size());
    lua_setfield(L, -2, "Referer");
  }
  std::vector<std::string> original_keys;
  for (const auto& kv : request->headers.GetHeaderVector()) {
    original_keys.push_back(kv.key);
  }
  // __luakit_web_dispatch("request", rid, uri, headers) -> verdict, headers
  Dispatch("request", 3);
  int nret = lua_gettop(L) - top;
  bool allow = true;
  if (nret >= 1) {
    if (lua_type(L, top + 1) == LUA_TSTRING) {
      GURL redirect(lua_tostring(L, top + 1));
      if (redirect.is_valid()) {
        request->url = redirect;
      }
    } else if (lua_type(L, top + 1) == LUA_TBOOLEAN &&
               !lua_toboolean(L, top + 1)) {
      allow = false;
    }
  }
  if (allow && nret >= 2 && lua_istable(L, top + 2)) {
    int ht = top + 2;
    // 删掉表里没有了的
    for (const std::string& k : original_keys) {
      lua_getfield(L, ht, k.c_str());
      if (lua_isnil(L, -1)) {
        request->headers.RemoveHeader(k);
      }
      lua_pop(L, 1);
    }
    if (had_referer) {
      lua_getfield(L, ht, "Referer");
      if (lua_isnil(L, -1)) {
        request->referrer = GURL();
      }
      lua_pop(L, 1);
    }
    // 写回/新增
    lua_pushnil(L);
    while (lua_next(L, ht) != 0) {
      if (lua_type(L, -2) == LUA_TSTRING && lua_type(L, -1) == LUA_TSTRING) {
        std::string key = lua_tostring(L, -2);
        std::string value = lua_tostring(L, -1);
        if (key == "Referer" || key == "referer") {
          request->referrer = GURL(value);
        } else {
          request->headers.SetHeader(key, value);
        }
      }
      lua_pop(L, 1);
    }
  }
  lua_settop(L, top);
  return allow;
}

// ---- mojom::LuakitWebExtension ----

void LuakitWebExtension::Init(const std::string& env_json) {
  env_json_ = env_json;
  EnsureState();
  lua_State* L = L_;
  int top = lua_gettop(L);
  lua_getglobal(L, "require");
  lua_pushstring(L, "lkw_init");
  if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "?";
    LOG(ERROR) << "luakit-web: kernel init failed: " << err;
    if (host_) {
      host_->Log(0, "web", "kernel init failed: " + err);
    }
    lua_settop(L, top);
    return;
  }
  lua_settop(L, top);
  inited_ = true;

  for (const std::string& m : pending_requires_) {
    RequireModule(m);
  }
  pending_requires_.clear();

  // 已经存在的主框架：现在补发 page-created
  for (auto& [rid, page] : pages_) {
    MaybeEmitPageCreated(&page);
  }
}

void LuakitWebExtension::RequireModule(const std::string& name) {
  if (!inited_) {
    pending_requires_.push_back(name);
    return;
  }
  lua_State* L = L_;
  int top = lua_gettop(L);
  lua_getglobal(L, "require");
  lua_pushstring(L, name.c_str());
  if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "?";
    LOG(WARNING) << "luakit-web: require_web_module(" << name
                 << ") failed: " << err;
    if (host_) {
      host_->Log(1, "web", "require_web_module(" + name + "): " + err);
    }
  }
  lua_settop(L, top);
}

void LuakitWebExtension::EmitSignal(const std::string& channel,
                                    int32_t page_id,
                                    const std::string& signame,
                                    const std::string& args_json) {
  if (!inited_) {
    return;
  }
  lua_State* L = L_;
  int top = lua_gettop(L);
  // __luakit_web_dispatch("ipc", channel, page_rid|nil, signame, args_json)
  lua_pushstring(L, channel.c_str());
  Page* p = page_id >= 0 ? PageById(page_id) : nullptr;
  if (p) {
    lua_pushinteger(L, p->routing_id);
  } else {
    lua_pushnil(L);
  }
  lua_pushstring(L, signame.c_str());
  lua_pushlstring(L, args_json.data(), args_json.size());
  Dispatch("ipc", 4);
  lua_settop(L, top);
}

void LuakitWebExtension::PageCreated(int32_t routing_id, int32_t page_id) {
  auto it = pages_.find(routing_id);
  if (it == pages_.end()) {
    pending_page_ids_[routing_id] = page_id;
    return;
  }
  it->second.page_id = page_id;
  MaybeEmitPageCreated(&it->second);
}

void LuakitWebExtension::EvalJs(int32_t page_id,
                                const std::string& script,
                                const std::string& source,
                                int32_t callback_id) {
  if (!inited_) {
    if (host_) {
      host_->EvalJsResult(callback_id, "null", "web extension not ready");
    }
    return;
  }
  Page* p = PageById(page_id);
  if (!p || !p->frame) {
    if (host_) {
      host_->EvalJsResult(callback_id, "null", "page not found");
    }
    return;
  }
  lua_State* L = L_;
  int top = lua_gettop(L);
  // __luakit_web_dispatch("eval", rid, script, source, cb_id)：Lua 负责回包
  lua_pushinteger(L, p->routing_id);
  lua_pushlstring(L, script.data(), script.size());
  lua_pushstring(L, source.c_str());
  lua_pushinteger(L, callback_id);
  Dispatch("eval", 4);
  lua_settop(L, top);
}

void LuakitWebExtension::Scroll(int32_t page_id, int32_t x, int32_t y) {
  Page* p = PageById(page_id);
  if (!p || !p->frame) {
    return;
  }
  Scoped s(this, p->routing_id);
  if (!s.ok()) {
    return;
  }
  v8::Local<v8::Value> fn;
  if (s.ctx->Global()->Get(s.ctx, V8Str(s.isolate, "scrollTo")).ToLocal(&fn) &&
      fn->IsFunction()) {
    v8::Local<v8::Value> argv[] = {v8::Integer::New(s.isolate, x),
                                   v8::Integer::New(s.isolate, y)};
    (void)fn.As<v8::Function>()->Call(s.ctx, s.ctx->Global(), 2, argv);
  }
}

// ---- 页面表 ----

LuakitWebExtension::Page* LuakitWebExtension::PageByRouting(int routing_id) {
  auto it = pages_.find(routing_id);
  return it == pages_.end() ? nullptr : &it->second;
}

LuakitWebExtension::Page* LuakitWebExtension::PageById(int page_id) {
  for (auto& [rid, p] : pages_) {
    if (p.page_id == page_id && p.frame) {
      return &p;
    }
  }
  return nullptr;
}

content::RenderFrame* LuakitWebExtension::FrameForPage(int page_id) {
  Page* p = PageById(page_id);
  return p ? p->frame : nullptr;
}

void LuakitWebExtension::OnFrameReady(content::RenderFrame* frame) {
  int rid = frame->GetRoutingID();
  Page& p = pages_[rid];
  p.frame = frame;
  p.routing_id = rid;
  auto pending = pending_page_ids_.find(rid);
  if (pending != pending_page_ids_.end()) {
    p.page_id = pending->second;
    pending_page_ids_.erase(pending);
  }
  MaybeEmitPageCreated(&p);
}

void LuakitWebExtension::MaybeEmitPageCreated(Page* page) {
  if (!inited_ || page->created_emitted || !page->frame || page->page_id < 0) {
    return;
  }
  page->created_emitted = true;
  lua_State* L = L_;
  int top = lua_gettop(L);
  lua_pushinteger(L, page->routing_id);
  lua_pushinteger(L, page->page_id);
  Dispatch("page-created", 2);
  lua_settop(L, top);
}

void LuakitWebExtension::OnFrameDestroyed(int routing_id) {
  auto it = pages_.find(routing_id);
  if (it == pages_.end()) {
    return;
  }
  bool emitted = it->second.created_emitted;
  it->second.frame = nullptr;
  if (inited_ && emitted) {
    lua_State* L = L_;
    int top = lua_gettop(L);
    lua_pushinteger(L, routing_id);
    Dispatch("page-destroyed", 1);
    lua_settop(L, top);
  }
  // 释放该帧的全部句柄 / 监听器
  for (auto h = handles_.begin(); h != handles_.end();) {
    if (h->second.routing_id == routing_id) {
      h = handles_.erase(h);
    } else {
      ++h;
    }
  }
  for (auto l = listeners_.begin(); l != listeners_.end();) {
    if (l->second.routing_id == routing_id) {
      l = listeners_.erase(l);
    } else {
      ++l;
    }
  }
  for (auto b = BoundTable().begin(); b != BoundTable().end();) {
    if (b->second.routing_id == routing_id) {
      b = BoundTable().erase(b);
    } else {
      ++b;
    }
  }
  for (auto i = identity_index_.begin(); i != identity_index_.end();) {
    if (i->first.first == routing_id) {
      i = identity_index_.erase(i);
    } else {
      ++i;
    }
  }
  pages_.erase(it);
}

void LuakitWebExtension::OnDocumentLoaded(int routing_id) {
  Page* p = PageByRouting(routing_id);
  if (!p || !inited_) {
    return;
  }
  if (!p->created_emitted) {
    MaybeEmitPageCreated(p);
  }
  if (!p->created_emitted) {
    return;
  }
  lua_State* L = L_;
  int top = lua_gettop(L);
  lua_pushinteger(L, routing_id);
  Dispatch("document-loaded", 1);
  lua_settop(L, top);
}

void LuakitWebExtension::OnWindowObjectCleared(int routing_id) {
  Page* p = PageByRouting(routing_id);
  if (!p || !inited_) {
    return;
  }
  // 新文档的 window：旧句柄全部失效
  for (auto h = handles_.begin(); h != handles_.end();) {
    if (h->second.routing_id == routing_id) {
      h = handles_.erase(h);
    } else {
      ++h;
    }
  }
  for (auto i = identity_index_.begin(); i != identity_index_.end();) {
    if (i->first.first == routing_id) {
      i = identity_index_.erase(i);
    } else {
      ++i;
    }
  }
  MaybeEmitPageCreated(p);
  if (!p->created_emitted) {
    return;
  }
  lua_State* L = L_;
  int top = lua_gettop(L);
  lua_pushinteger(L, routing_id);
  std::string uri = "about:blank";
  if (p->frame) {
    GURL url(p->frame->GetWebFrame()->GetDocument().Url());
    if (url.is_valid()) {
      uri = url.spec();
    }
  }
  lua_pushstring(L, uri.c_str());
  Dispatch("window-object-cleared", 2);
  lua_settop(L, top);
}

// ---- 句柄表 ----

v8::Isolate* LuakitWebExtension::IsolateFor(int routing_id) {
  Page* p = PageByRouting(routing_id);
  if (!p || !p->frame) {
    return nullptr;
  }
  blink::WebLocalFrame* wf = p->frame->GetWebFrame();
  if (!wf || !wf->GetAgentGroupScheduler()) {
    return nullptr;
  }
  return wf->GetAgentGroupScheduler()->Isolate();
}

v8::Local<v8::Context> LuakitWebExtension::ContextFor(int routing_id) {
  Page* p = PageByRouting(routing_id);
  if (!p || !p->frame) {
    return v8::Local<v8::Context>();
  }
  blink::WebLocalFrame* wf = p->frame->GetWebFrame();
  if (!wf) {
    return v8::Local<v8::Context>();
  }
  return wf->MainWorldScriptContext();
}

int LuakitWebExtension::RoutingIdForHandle(int handle) const {
  auto it = handles_.find(handle);
  return it == handles_.end() ? 0 : it->second.routing_id;
}

int LuakitWebExtension::RetainValue(int routing_id, v8::Local<v8::Value> value) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  int identity = 0;
  if (value->IsObject()) {
    identity = value.As<v8::Object>()->GetIdentityHash();
    auto key = std::make_pair(routing_id, identity);
    auto it = identity_index_.find(key);
    if (it != identity_index_.end()) {
      for (int h : it->second) {
        auto hi = handles_.find(h);
        if (hi != handles_.end() &&
            hi->second.value.Get(isolate)->StrictEquals(value)) {
          return h;
        }
      }
    }
  }
  int h = next_handle_++;
  Handle& entry = handles_[h];
  entry.value.Reset(isolate, value);
  entry.routing_id = routing_id;
  entry.identity = identity;
  if (value->IsObject()) {
    identity_index_[std::make_pair(routing_id, identity)].push_back(h);
  }
  return h;
}

bool LuakitWebExtension::LookupValue(int handle,
                                     v8::Local<v8::Value>* out,
                                     int* routing_id) {
  auto it = handles_.find(handle);
  if (it == handles_.end()) {
    return false;
  }
  *routing_id = it->second.routing_id;
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  if (isolate) {
    *out = it->second.value.Get(isolate);
  }
  return true;
}

void LuakitWebExtension::ReleaseValue(int handle) {
  auto it = handles_.find(handle);
  if (it == handles_.end()) {
    return;
  }
  if (it->second.identity) {
    auto key = std::make_pair(it->second.routing_id, it->second.identity);
    auto ii = identity_index_.find(key);
    if (ii != identity_index_.end()) {
      auto& vec = ii->second;
      vec.erase(std::remove(vec.begin(), vec.end(), handle), vec.end());
      if (vec.empty()) {
        identity_index_.erase(ii);
      }
    }
  }
  handles_.erase(it);
}

void LuakitWebExtension::StoreListener(int cb_id,
                                       int routing_id,
                                       v8::Local<v8::Function> fn) {
  Listener& l = listeners_[cb_id];
  l.fn.Reset(v8::Isolate::GetCurrent(), fn);
  l.routing_id = routing_id;
}

bool LuakitWebExtension::TakeListener(int cb_id,
                                      v8::Local<v8::Function>* out,
                                      int* routing_id) {
  auto it = listeners_.find(cb_id);
  if (it == listeners_.end()) {
    return false;
  }
  *routing_id = it->second.routing_id;
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  if (isolate) {
    *out = it->second.fn.Get(isolate);
  }
  return true;
}

void LuakitWebExtension::DropListener(int cb_id) {
  listeners_.erase(cb_id);
}

// ---- 值转换 ----

v8::Local<v8::Value> LuakitWebExtension::LuaToV8(lua_State* L,
                                                 int idx,
                                                 v8::Local<v8::Context> ctx,
                                                 int routing_id) {
  v8::Isolate* isolate = ctx->GetIsolate();
  idx = lua_absindex(L, idx);
  switch (lua_type(L, idx)) {
    case LUA_TNIL:
    case LUA_TNONE:
      return v8::Null(isolate);
    case LUA_TBOOLEAN:
      return v8::Boolean::New(isolate, lua_toboolean(L, idx) != 0);
    case LUA_TNUMBER:
      return v8::Number::New(isolate, lua_tonumber(L, idx));
    case LUA_TSTRING: {
      size_t len = 0;
      const char* s = lua_tolstring(L, idx, &len);
      return V8Str(isolate, std::string(s, len));
    }
    case LUA_TTABLE: {
      int h = HandleAt(L, idx);
      if (h) {
        v8::Local<v8::Value> v;
        int rid = 0;
        if (LookupValue(h, &v, &rid)) {
          return v;
        }
        return v8::Undefined(isolate);
      }
      // dom_element / dom_document 等内核对象：约定 rawget "__js" 指向句柄表
      lua_pushstring(L, "__js");
      lua_rawget(L, idx);
      if (lua_istable(L, -1)) {
        v8::Local<v8::Value> v = LuaToV8(L, -1, ctx, routing_id);
        lua_pop(L, 1);
        return v;
      }
      lua_pop(L, 1);

      lua_Integer n = static_cast<lua_Integer>(lua_rawlen(L, idx));
      bool is_array = true;
      lua_pushnil(L);
      while (lua_next(L, idx)) {
        if (!lua_isinteger(L, -2) || lua_tointeger(L, -2) < 1 ||
            lua_tointeger(L, -2) > n) {
          is_array = false;
          lua_pop(L, 2);
          break;
        }
        lua_pop(L, 1);
      }
      if (is_array) {
        v8::Local<v8::Array> arr = v8::Array::New(isolate, static_cast<int>(n));
        for (lua_Integer i = 1; i <= n; ++i) {
          lua_rawgeti(L, idx, i);
          (void)arr->Set(ctx, static_cast<uint32_t>(i - 1),
                         LuaToV8(L, -1, ctx, routing_id));
          lua_pop(L, 1);
        }
        return arr;
      }
      v8::Local<v8::Object> obj = v8::Object::New(isolate);
      lua_pushnil(L);
      while (lua_next(L, idx)) {
        v8::Local<v8::Value> key;
        if (lua_type(L, -2) == LUA_TSTRING) {
          key = V8Str(isolate, lua_tostring(L, -2));
        } else if (lua_isnumber(L, -2)) {
          key = v8::Number::New(isolate, lua_tonumber(L, -2));
        }
        if (!key.IsEmpty()) {
          (void)obj->Set(ctx, key, LuaToV8(L, -1, ctx, routing_id));
        }
        lua_pop(L, 1);
      }
      return obj;
    }
    case LUA_TFUNCTION: {
      // 由 Lua 内核先包成 js_function 句柄；裸函数到这里就退化成 undefined
      return v8::Undefined(isolate);
    }
    default:
      return v8::Undefined(isolate);
  }
}

void LuakitWebExtension::V8ToLua(lua_State* L,
                                 v8::Local<v8::Value> value,
                                 v8::Local<v8::Context> ctx,
                                 int routing_id,
                                 int depth) {
  v8::Isolate* isolate = ctx->GetIsolate();
  if (value.IsEmpty() || value->IsNullOrUndefined()) {
    lua_pushnil(L);
    return;
  }
  if (value->IsBoolean()) {
    lua_pushboolean(L, value->BooleanValue(isolate));
    return;
  }
  if (value->IsNumber()) {
    double d = value->NumberValue(ctx).FromMaybe(0);
    if (std::isfinite(d) && d == std::floor(d) && std::fabs(d) < 9e15) {
      lua_pushinteger(L, static_cast<lua_Integer>(d));
    } else {
      lua_pushnumber(L, d);
    }
    return;
  }
  if (value->IsString()) {
    std::string s = ToStd(isolate, value);
    lua_pushlstring(L, s.data(), s.size());
    return;
  }
  if (value->IsFunction()) {
    PushHandleTable(L, RetainValue(routing_id, value), "function");
    return;
  }
  if (value->IsArray() && depth < kMaxDepth) {
    v8::Local<v8::Array> arr = value.As<v8::Array>();
    lua_createtable(L, static_cast<int>(arr->Length()), 0);
    for (uint32_t i = 0; i < arr->Length(); ++i) {
      v8::Local<v8::Value> item;
      if (arr->Get(ctx, i).ToLocal(&item)) {
        V8ToLua(L, item, ctx, routing_id, depth + 1);
      } else {
        lua_pushnil(L);
      }
      lua_rawseti(L, -2, static_cast<lua_Integer>(i) + 1);
    }
    return;
  }
  if (value->IsObject()) {
    v8::Local<v8::Object> obj = value.As<v8::Object>();
    // DOM 节点：句柄，Lua 侧包成 dom_element / dom_document
    v8::Local<v8::Value> node_type;
    if (obj->Get(ctx, V8Str(isolate, "nodeType")).ToLocal(&node_type) &&
        node_type->IsNumber()) {
      PushHandleTable(L, RetainValue(routing_id, value), "node");
      return;
    }
    // 类数组集合（querySelectorAll / getClientRects / children …）：像 Array 一样
    // 展开成 Lua 序列，元素递归转换（节点变句柄）。luakit 的 query() 也返回表。
    if (depth < kMaxDepth) {
      static constexpr const char* kArrayLikes[] = {
          "NodeList",      "HTMLCollection", "DOMRectList", "DOMTokenList",
          "DOMStringList", "NamedNodeMap",   "FileList",    "TouchList",
          "StyleSheetList", "CSSRuleList",   "HTMLFormControlsCollection",
          "HTMLOptionsCollection", "HTMLAllCollection"};
      std::string ctor = ToStd(isolate, obj->GetConstructorName());
      bool array_like = false;
      for (const char* name : kArrayLikes) {
        if (ctor == name) {
          array_like = true;
          break;
        }
      }
      v8::Local<v8::Value> len;
      if (array_like &&
          obj->Get(ctx, V8Str(isolate, "length")).ToLocal(&len) &&
          len->IsNumber()) {
        uint32_t n = static_cast<uint32_t>(len->NumberValue(ctx).FromMaybe(0));
        lua_createtable(L, static_cast<int>(n), 0);
        for (uint32_t i = 0; i < n; ++i) {
          v8::Local<v8::Value> item;
          if (obj->Get(ctx, i).ToLocal(&item)) {
            V8ToLua(L, item, ctx, routing_id, depth + 1);
          } else {
            lua_pushnil(L);
          }
          lua_rawseti(L, -2, static_cast<lua_Integer>(i) + 1);
        }
        return;
      }
    }
    // 纯对象（原型是 Object.prototype）：转成表；其它（Event、Window、Promise…）：句柄
    bool plain = false;
    v8::Local<v8::Value> object_ctor;
    if (ctx->Global()->Get(ctx, V8Str(isolate, "Object")).ToLocal(&object_ctor) &&
        object_ctor->IsObject()) {
      v8::Local<v8::Value> proto;
      if (object_ctor.As<v8::Object>()
              ->Get(ctx, V8Str(isolate, "prototype"))
              .ToLocal(&proto)) {
        plain = obj->GetPrototype()->StrictEquals(proto);
      }
    }
    if (!plain || depth >= kMaxDepth) {
      PushHandleTable(L, RetainValue(routing_id, value), "object");
      return;
    }
    lua_newtable(L);
    v8::Local<v8::Array> names;
    if (obj->GetOwnPropertyNames(ctx).ToLocal(&names)) {
      for (uint32_t i = 0; i < names->Length(); ++i) {
        v8::Local<v8::Value> key;
        v8::Local<v8::Value> item;
        if (!names->Get(ctx, i).ToLocal(&key) ||
            !obj->Get(ctx, key).ToLocal(&item)) {
          continue;
        }
        std::string k = ToStd(isolate, key);
        V8ToLua(L, item, ctx, routing_id, depth + 1);
        lua_setfield(L, -2, k.c_str());
      }
    }
    return;
  }
  lua_pushnil(L);
}

}  // namespace lemurx
