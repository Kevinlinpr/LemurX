// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_api.h"

#include <jni.h>

#include <cstring>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "third_party/jni_zero/jni_zero.h"
#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/time/time.h"
#include "base/values.h"
#include "chrome/android/chrome_jni_headers/LemurXBridge_jni.h"
#include "chrome/browser/lemurx/lemurx_cookies.h"
#include "chrome/browser/lemurx/lemurx_net_rules.h"
#include "chrome/browser/ui/android/lemurx/lemurx_cdp.h"
#include "chrome/browser/ui/android/lemurx/lemurx_engine.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_native.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_scheme.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_web_host.h"
#include "chrome/browser/ui/android/lemurx/lemurx_luakit_webview.h"
#include "content/public/browser/web_contents.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"
#include "url/gurl.h"

namespace {

void PushValue(lua_State* L, const base::Value& value) {
  switch (value.type()) {
    case base::Value::Type::NONE:
      lua_pushnil(L);
      break;
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
    case base::Value::Type::DICT: {
      lua_newtable(L);
      for (const auto& item : value.GetDict()) {
        PushValue(L, item.second);
        lua_setfield(L, -2, item.first.c_str());
      }
      break;
    }
    case base::Value::Type::LIST: {
      lua_newtable(L);
      lua_Integer i = 1;
      for (const auto& child : value.GetList()) {
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

int PushJson(lua_State* L, const std::string& json) {
  if (json.empty() || json == "null") {
    lua_pushnil(L);
    return 1;
  }
  std::optional<base::Value> value = base::JSONReader::Read(json, base::JSON_PARSE_RFC);
  if (!value) {
    lua_pushlstring(L, json.data(), json.size());
    return 1;
  }
  PushValue(L, *value);
  return 1;
}

base::Value LuaToValue(lua_State* L, int index);

base::Value LuaTableToValue(lua_State* L, int index) {
  index = lua_absindex(L, index);
  bool is_array = true;
  lua_Integer expected = 1;
  lua_pushnil(L);
  while (lua_next(L, index) != 0) {
    if (lua_type(L, -2) != LUA_TNUMBER || !lua_isinteger(L, -2) ||
        lua_tointeger(L, -2) != expected) {
      is_array = false;
      lua_pop(L, 2);
      break;
    }
    expected++;
    lua_pop(L, 1);
  }

  if (is_array && expected > 1) {
    base::ListValue list;
    lua_pushnil(L);
    while (lua_next(L, index) != 0) {
      list.Append(LuaToValue(L, -1));
      lua_pop(L, 1);
    }
    return base::Value(std::move(list));
  }

  base::DictValue dict;
  lua_pushnil(L);
  while (lua_next(L, index) != 0) {
    std::string key;
    if (lua_type(L, -2) == LUA_TSTRING) {
      key = lua_tostring(L, -2);
    } else if (lua_type(L, -2) == LUA_TNUMBER) {
      key = std::to_string(lua_tonumber(L, -2));
    } else {
      lua_pop(L, 1);
      continue;
    }
    dict.Set(key, LuaToValue(L, -1));
    lua_pop(L, 1);
  }
  return base::Value(std::move(dict));
}

base::Value LuaToValue(lua_State* L, int index) {
  switch (lua_type(L, index)) {
    case LUA_TNIL:
      return base::Value();
    case LUA_TBOOLEAN:
      return base::Value(lua_toboolean(L, index) != 0);
    case LUA_TNUMBER:
      if (lua_isinteger(L, index)) {
        return base::Value(static_cast<int>(lua_tointeger(L, index)));
      }
      return base::Value(lua_tonumber(L, index));
    case LUA_TSTRING:
      return base::Value(lua_tostring(L, index));
    case LUA_TTABLE:
      return LuaTableToValue(L, index);
    default: {
      size_t len = 0;
      const char* s = luaL_tolstring(L, index, &len);
      std::string str = s ? std::string(s, len) : "";
      lua_pop(L, 1);
      return base::Value(std::move(str));
    }
  }
}

std::string LuaToJson(lua_State* L, int index) {
  std::string json;
  base::JSONWriter::Write(LuaToValue(L, index), &json);
  return json.empty() ? "{}" : json;
}

std::string JavaString(JNIEnv* env,
                       const jni_zero::ScopedJavaLocalRef<jstring>& jstr) {
  if (jstr.is_null()) {
    return "";
  }
  return base::android::ConvertJavaStringToUTF8(env, jstr);
}

int LuaLog(lua_State* L) {
  int n = lua_gettop(L);
  std::string msg;
  for (int i = 1; i <= n; ++i) {
    if (i > 1) {
      msg += "\t";
    }
    size_t len = 0;
    const char* s = luaL_tolstring(L, i, &len);
    if (s) {
      msg.append(s, len);
    }
    lua_pop(L, 1);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_log(
      env, base::android::ConvertUTF8ToJavaString(env, msg));
  return 0;
}

int LuaToast(lua_State* L) {
  const char* msg = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_toast(
      env, base::android::ConvertUTF8ToJavaString(env, msg));
  return 0;
}

int LuaBrowserInfo(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_browserInfo(env)));
}

int LuaTabsList(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_listTabs(env)));
}

int LuaTabsCurrent(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_currentTab(env)));
}

int LuaTabsOpen(lua_State* L) {
  const char* url = luaL_checkstring(L, 1);
  std::string options = "{}";
  if (lua_istable(L, 2)) {
    options = LuaToJson(L, 2);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  int tab_id = Java_LemurXBridge_openTab(
      env, base::android::ConvertUTF8ToJavaString(env, url),
      base::android::ConvertUTF8ToJavaString(env, options));
  lua_pushinteger(L, tab_id);
  return 1;
}

int LuaTabsClose(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_closeTab(env, tab_id));
  return 1;
}

int LuaTabsSelect(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_selectTab(env, tab_id));
  return 1;
}

int LuaTabsNavigate(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  const char* url = luaL_checkstring(L, 2);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_navigateTab(
                         env, tab_id,
                         base::android::ConvertUTF8ToJavaString(env, url)));
  return 1;
}

int LuaTabsReload(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_reloadTab(env, tab_id));
  return 1;
}

int LuaTabsBack(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_goBack(env, tab_id));
  return 1;
}

int LuaTabsForward(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_goForward(env, tab_id));
  return 1;
}

int LuaTabsEval(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  const char* js = luaL_checkstring(L, 2);
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_evalJavaScript(
                                         env, tab_id,
                                         base::android::ConvertUTF8ToJavaString(
                                             env, js))));
}

int LuaTabsHide(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_hideTab(env, tab_id));
  return 1;
}

int LuaTabsShow(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_showTab(env, tab_id));
  return 1;
}

int LuaTabsFreeze(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_freezeTab(env, tab_id));
  return 1;
}

int LuaTabsInject(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  const char* js = luaL_checkstring(L, 2);
  bool isolated = true;
  bool all_frames = false;
  if (lua_istable(L, 3)) {
    lua_getfield(L, 3, "isolated");
    if (lua_isboolean(L, -1)) {
      isolated = lua_toboolean(L, -1) != 0;
    }
    lua_pop(L, 1);
    lua_getfield(L, 3, "world");
    if (lua_isstring(L, -1) && strcmp(lua_tostring(L, -1), "main") == 0) {
      isolated = false;
    }
    lua_pop(L, 1);
    lua_getfield(L, 3, "frames");
    if (lua_isstring(L, -1) && strcmp(lua_tostring(L, -1), "all") == 0) {
      all_frames = true;
    }
    if (lua_isboolean(L, -1)) {
      all_frames = lua_toboolean(L, -1) != 0;
    }
    lua_pop(L, 1);
  } else if (lua_isboolean(L, 3)) {
    isolated = lua_toboolean(L, 3) != 0;
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_injectJavaScript(
                             env, tab_id,
                             base::android::ConvertUTF8ToJavaString(env, js),
                             isolated, all_frames)));
}

std::string LuaFieldString(lua_State* L, int index, const char* key) {
  lua_getfield(L, index, key);
  std::string value;
  if (lua_isstring(L, -1)) {
    value = lua_tostring(L, -1);
  }
  lua_pop(L, 1);
  return value;
}

void LuaFieldStringMap(lua_State* L,
                       int index,
                       const char* key,
                       std::map<std::string, std::string>* out) {
  lua_getfield(L, index, key);
  if (lua_istable(L, -1)) {
    lua_pushnil(L);
    while (lua_next(L, -2) != 0) {
      if (lua_type(L, -2) == LUA_TSTRING && lua_isstring(L, -1)) {
        (*out)[lua_tostring(L, -2)] = lua_tostring(L, -1);
      }
      lua_pop(L, 1);
    }
  }
  lua_pop(L, 1);
}

void LuaFieldStringList(lua_State* L,
                        int index,
                        const char* key,
                        std::vector<std::string>* out) {
  lua_getfield(L, index, key);
  if (lua_istable(L, -1)) {
    lua_Integer n = luaL_len(L, -1);
    for (lua_Integer i = 1; i <= n; ++i) {
      lua_rawgeti(L, -1, i);
      if (lua_isstring(L, -1)) {
        out->emplace_back(lua_tostring(L, -1));
      }
      lua_pop(L, 1);
    }
  }
  lua_pop(L, 1);
}

void LuaFieldReplacePairs(lua_State* L,
                          int index,
                          const char* key,
                          std::vector<std::pair<std::string, std::string>>* out) {
  lua_getfield(L, index, key);
  if (!lua_istable(L, -1)) {
    lua_pop(L, 1);
    return;
  }
  lua_Integer n = luaL_len(L, -1);
  if (n > 0) {
    for (lua_Integer i = 1; i <= n; ++i) {
      lua_rawgeti(L, -1, i);
      if (lua_istable(L, -1)) {
        std::string from = LuaFieldString(L, -1, "from");
        if (from.empty()) {
          from = LuaFieldString(L, -1, "find");
        }
        std::string to = LuaFieldString(L, -1, "to");
        if (to.empty()) {
          to = LuaFieldString(L, -1, "replace");
        }
        if (!from.empty()) {
          out->emplace_back(std::move(from), std::move(to));
        }
      }
      lua_pop(L, 1);
    }
  } else {
    lua_pushnil(L);
    while (lua_next(L, -2) != 0) {
      if (lua_isstring(L, -2) && lua_isstring(L, -1)) {
        out->emplace_back(lua_tostring(L, -2), lua_tostring(L, -1));
      }
      lua_pop(L, 1);
    }
  }
  lua_pop(L, 1);
}

int LuaNetAddRule(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  LemurXNetRules::Rule rule;
  rule.match = LuaFieldString(L, 1, "match");
  if (rule.match.empty()) {
    return luaL_error(L, "lemurx.net.addRule: match is required");
  }
  std::string action = LuaFieldString(L, 1, "action");
  rule.action = LemurXNetRules::ParseAction(action);
  std::string redirect = LuaFieldString(L, 1, "redirectUrl");
  if (!redirect.empty()) {
    rule.redirect_url = GURL(redirect);
  }
  LuaFieldStringList(L, 1, "types", &rule.types);
  LuaFieldStringMap(L, 1, "requestHeaders", &rule.request_headers);
  LuaFieldStringList(L, 1, "removeRequestHeaders",
                     &rule.remove_request_headers);
  LuaFieldStringMap(L, 1, "responseHeaders", &rule.response_headers);
  LuaFieldStringList(L, 1, "removeResponseHeaders",
                     &rule.remove_response_headers);
  LuaFieldReplacePairs(L, 1, "replaceBody", &rule.replace_body);
  rule.request_body = LuaFieldString(L, 1, "requestBody");
  LuaFieldReplacePairs(L, 1, "replaceRequestBody", &rule.replace_request_body);
  if (!rule.request_body.empty() || !rule.replace_request_body.empty()) {
    if (!LemurXEngine::Get()->privileged()) {
      return luaL_error(
          L, "lemurx.net requestBody is local-only (not allowed in UGC scripts)");
    }
  }
  if (action.empty() &&
      (!rule.replace_body.empty() || rule.NeedsRequestBodyRewrite())) {
    rule.action = LemurXNetRules::Action::kModify;
  }
  lua_pushinteger(L, LemurXNetRules::Get()->AddRule(std::move(rule)));
  return 1;
}

int LuaNetRemoveRule(lua_State* L) {
  int id = luaL_checkinteger(L, 1);
  lua_pushboolean(L, LemurXNetRules::Get()->RemoveRule(id));
  return 1;
}

int LuaNetClearRules(lua_State* L) {
  LemurXNetRules::Get()->Clear();
  return 0;
}

int LuaNetListRules(lua_State* L) {
  base::ListValue list;
  for (const auto& rule : LemurXNetRules::Get()->List()) {
    base::DictValue dict;
    dict.Set("id", rule.id);
    dict.Set("match", rule.match);
    dict.Set("action", LemurXNetRules::ActionName(rule.action));
    if (rule.redirect_url.is_valid()) {
      dict.Set("redirectUrl", rule.redirect_url.spec());
    }
    if (!rule.replace_body.empty()) {
      base::DictValue body;
      for (const auto& item : rule.replace_body) {
        body.Set(item.first, item.second);
      }
      dict.Set("replaceBody", std::move(body));
    }
    list.Append(std::move(dict));
  }
  std::string json;
  base::JSONWriter::Write(list, &json);
  return PushJson(L, json);
}

int LuaClipboardSet(lua_State* L) {
  const char* text = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_setClipboard(
      env, base::android::ConvertUTF8ToJavaString(env, text));
  return 0;
}

int LuaClipboardGet(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string text = JavaString(env, Java_LemurXBridge_getClipboard(env));
  lua_pushlstring(L, text.data(), text.size());
  return 1;
}

int LuaStorageGet(lua_State* L) {
  const char* key = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string value = JavaString(
      env, Java_LemurXBridge_storageGet(
               env, base::android::ConvertUTF8ToJavaString(env, key)));
  if (value.empty() && !lua_isnoneornil(L, 2)) {
    lua_pushvalue(L, 2);
    return 1;
  }
  lua_pushlstring(L, value.data(), value.size());
  return 1;
}

int LuaStorageSet(lua_State* L) {
  const char* key = luaL_checkstring(L, 1);
  const char* value = luaL_checkstring(L, 2);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_storageSet(
      env, base::android::ConvertUTF8ToJavaString(env, key),
      base::android::ConvertUTF8ToJavaString(env, value));
  return 0;
}

int LuaStorageDelete(lua_State* L) {
  const char* key = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_storageDelete(
      env, base::android::ConvertUTF8ToJavaString(env, key));
  return 0;
}

int LuaStorageList(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_storageList(env)));
}

// 一个 Lua 回调：ref 属于哪个 lua_State 就必须在哪个状态里调，
// 本地脚本和每个 UGC 脚本各自一个状态。
struct LuaCb {
  lua_State* L = nullptr;
  int ref = LUA_NOREF;
  bool privileged = true;
};

LuaCb MakeCb(lua_State* L, int ref) {
  return LuaCb{L, ref, LemurXEngine::Get()->privileged()};
}

struct TimerState {
  LuaCb cb;
  int interval_ms = 0;
  bool cancelled = false;
};

std::map<std::string, LuaCb> g_ui_click_refs;
std::map<std::string, std::vector<LuaCb>> g_tab_events;
std::map<std::string, std::vector<LuaCb>> g_cdp_events;
std::map<int, TimerState> g_timers;
int g_next_timer_id = 1;

void RequirePrivilege(lua_State* L, const char* api) {
  if (!LemurXEngine::Get()->privileged()) {
    luaL_error(L, "%s is local-only (not allowed in UGC scripts)", api);
  }
}

// 参数里的 L 只是调用方所在状态；真正 unref 用回调自己记的状态。
void UnrefUiClick(lua_State* L, const std::string& overlay_id) {
  auto it = g_ui_click_refs.find(overlay_id);
  if (it == g_ui_click_refs.end()) {
    return;
  }
  luaL_unref(it->second.L ? it->second.L : L, LUA_REGISTRYINDEX,
             it->second.ref);
  g_ui_click_refs.erase(it);
}

void UnrefAllUiClicks(lua_State* L) {
  for (const auto& item : g_ui_click_refs) {
    luaL_unref(item.second.L ? item.second.L : L, LUA_REGISTRYINDEX,
               item.second.ref);
  }
  g_ui_click_refs.clear();
}

void DispatchUiClickOnLuaThread(std::string overlay_id, std::string json) {
  auto it = g_ui_click_refs.find(overlay_id);
  if (it == g_ui_click_refs.end()) {
    return;
  }
  lua_State* L = it->second.L;
  if (!L) {
    return;
  }
  LemurXEngine::ScopedState scope(LemurXEngine::Get(), L,
                                    it->second.privileged);
  lua_rawgeti(L, LUA_REGISTRYINDEX, it->second.ref);
  int nargs = 0;
  if (!json.empty() && json != "{}") {
    PushJson(L, json);
    nargs = 1;
  }
  if (lua_pcall(L, nargs, 0, 0) != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "ui click";
    lua_pop(L, 1);
    LOG(ERROR) << "LemurX ui click: " << err;
  }
}

void DispatchTabEventOnLuaThread(std::string name, std::string json) {
  auto it = g_tab_events.find(name);
  if (it == g_tab_events.end()) {
    return;
  }
  std::vector<LuaCb> cbs = it->second;
  for (const auto& cb : cbs) {
    lua_State* L = cb.L;
    if (!L) {
      continue;
    }
    LemurXEngine::ScopedState scope(LemurXEngine::Get(), L,
                                      cb.privileged);
    lua_rawgeti(L, LUA_REGISTRYINDEX, cb.ref);
    PushJson(L, json);
    if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
      std::string err =
          lua_tostring(L, -1) ? lua_tostring(L, -1) : "tab event";
      lua_pop(L, 1);
      LOG(ERROR) << "LemurX tab event: " << err;
    }
  }
}

void DispatchCdpEventOnLuaThread(std::string name, std::string json) {
  std::vector<LuaCb> cbs;
  auto it = g_cdp_events.find(name);
  if (it != g_cdp_events.end()) {
    cbs.insert(cbs.end(), it->second.begin(), it->second.end());
  }
  auto all = g_cdp_events.find("*");
  if (all != g_cdp_events.end()) {
    cbs.insert(cbs.end(), all->second.begin(), all->second.end());
  }
  for (const auto& cb : cbs) {
    lua_State* L = cb.L;
    if (!L) {
      continue;
    }
    LemurXEngine::ScopedState scope(LemurXEngine::Get(), L,
                                      cb.privileged);
    lua_rawgeti(L, LUA_REGISTRYINDEX, cb.ref);
    PushJson(L, json);
    if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
      std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "cdp event";
      lua_pop(L, 1);
      LOG(ERROR) << "LemurX cdp event: " << err;
    }
  }
}

void FireTimerOnLuaThread(int id);

void ScheduleTimer(int id, int delay_ms) {
  LemurXEngine::Get()->PostDelayedOnLuaThread(
      base::BindOnce(&FireTimerOnLuaThread, id),
      base::Milliseconds(delay_ms));
}

void FireTimerOnLuaThread(int id) {
  auto it = g_timers.find(id);
  if (it == g_timers.end()) {
    return;
  }
  lua_State* L = it->second.cb.L;
  if (!L) {
    g_timers.erase(it);
    return;
  }
  if (it->second.cancelled) {
    luaL_unref(L, LUA_REGISTRYINDEX, it->second.cb.ref);
    g_timers.erase(it);
    return;
  }
  int interval_ms = it->second.interval_ms;
  LemurXEngine::ScopedState scope(LemurXEngine::Get(), L,
                                    it->second.cb.privileged);
  lua_rawgeti(L, LUA_REGISTRYINDEX, it->second.cb.ref);
  if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "timer";
    lua_pop(L, 1);
    LOG(ERROR) << "LemurX timer: " << err;
  }
  it = g_timers.find(id);
  if (it == g_timers.end() || it->second.cancelled || interval_ms <= 0) {
    if (it != g_timers.end()) {
      luaL_unref(L, LUA_REGISTRYINDEX, it->second.cb.ref);
      g_timers.erase(it);
    }
    return;
  }
  ScheduleTimer(id, interval_ms);
}

int LuaUiShow(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  lua_getfield(L, 1, "onClick");
  int click_ref = LUA_NOREF;
  if (lua_isfunction(L, -1)) {
    click_ref = luaL_ref(L, LUA_REGISTRYINDEX);
  } else {
    lua_pop(L, 1);
  }
  std::string options = LuaToJson(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string overlay_id = JavaString(
      env, Java_LemurXBridge_uiShow(
               env, base::android::ConvertUTF8ToJavaString(env, options)));
  if (overlay_id.empty()) {
    if (click_ref != LUA_NOREF) {
      luaL_unref(L, LUA_REGISTRYINDEX, click_ref);
    }
    lua_pushnil(L);
    return 1;
  }
  UnrefUiClick(L, overlay_id);
  if (click_ref != LUA_NOREF) {
    g_ui_click_refs[overlay_id] =
        MakeCb(L, click_ref);
  }
  lua_pushlstring(L, overlay_id.data(), overlay_id.size());
  return 1;
}

int LuaUiRemove(lua_State* L) {
  const char* overlay_id = luaL_checkstring(L, 1);
  UnrefUiClick(L, overlay_id);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_uiRemove(
             env, base::android::ConvertUTF8ToJavaString(env, overlay_id)));
  return 1;
}

int LuaUiClear(lua_State* L) {
  UnrefAllUiClicks(L);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_uiClear(env);
  return 0;
}

int LuaUiOp(lua_State* L, const char* action) {
  std::string options = "{}";
  if (lua_istable(L, 1)) {
    options = LuaToJson(L, 1);
    if (lua_isstring(L, 2) && strcmp(action, "settext") == 0) {
      std::optional<base::Value> parsed = base::JSONReader::Read(options, base::JSON_PARSE_RFC);
      base::DictValue dict;
      if (parsed && parsed->is_dict()) {
        dict = std::move(parsed->GetDict());
      }
      dict.Set("value", lua_tostring(L, 2));
      base::JSONWriter::Write(base::Value(std::move(dict)), &options);
    } else if (lua_isboolean(L, 2)
               && (strcmp(action, "visible") == 0 || strcmp(action, "enabled") == 0)) {
      std::optional<base::Value> parsed = base::JSONReader::Read(options, base::JSON_PARSE_RFC);
      base::DictValue dict;
      if (parsed && parsed->is_dict()) {
        dict = std::move(parsed->GetDict());
      }
      dict.Set(action, lua_toboolean(L, 2) != 0);
      base::JSONWriter::Write(base::Value(std::move(dict)), &options);
    }
  } else if (lua_isstring(L, 1)) {
    base::DictValue dict;
    dict.Set("text", lua_tostring(L, 1));
    if (lua_isstring(L, 2) && strcmp(action, "settext") == 0) {
      dict.Set("value", lua_tostring(L, 2));
    }
    base::JSONWriter::Write(base::Value(std::move(dict)), &options);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_uiOp(
                             env, base::android::ConvertUTF8ToJavaString(env, action),
                             base::android::ConvertUTF8ToJavaString(env, options))));
}

int CallUiOp(lua_State* L, const char* action, const std::string& json) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_uiOp(
                             env, base::android::ConvertUTF8ToJavaString(env, action),
                             base::android::ConvertUTF8ToJavaString(env, json))));
}

// 声明式控件树：把节点上的 onClick/onChange/onLongClick 收进注册表，
// 表里替换成 true，节点没有 id 就补一个，Java 那边按 id 回调。
void CollectUiHandlers(lua_State* L, int index, int* counter) {
  index = lua_absindex(L, index);
  if (!lua_istable(L, index)) {
    return;
  }
  static const struct {
    const char* field;
    const char* suffix;
  } kHandlers[] = {
      {"onClick", ""},
      {"onChange", ":change"},
      {"onLongClick", ":long"},
  };
  bool has_handler = false;
  for (const auto& handler : kHandlers) {
    lua_getfield(L, index, handler.field);
    bool is_fn = lua_isfunction(L, -1);
    lua_pop(L, 1);
    if (is_fn) {
      has_handler = true;
      break;
    }
  }
  if (has_handler) {
    lua_getfield(L, index, "id");
    std::string id;
    if (lua_isstring(L, -1)) {
      id = lua_tostring(L, -1);
    }
    lua_pop(L, 1);
    if (id.empty()) {
      id = "ui_" + std::to_string(++(*counter)) + "_" +
           std::to_string(base::Time::Now().ToTimeT() % 100000);
      lua_pushstring(L, id.c_str());
      lua_setfield(L, index, "id");
    }
    for (const auto& handler : kHandlers) {
      lua_getfield(L, index, handler.field);
      if (lua_isfunction(L, -1)) {
        std::string key = id + handler.suffix;
        UnrefUiClick(L, key);
        int ref = luaL_ref(L, LUA_REGISTRYINDEX);
        g_ui_click_refs[key] = MakeCb(L, ref);
        lua_pushboolean(L, 1);
        lua_setfield(L, index, handler.field);
      } else {
        lua_pop(L, 1);
      }
    }
  }
  lua_getfield(L, index, "children");
  if (lua_istable(L, -1)) {
    int children = lua_absindex(L, -1);
    lua_pushnil(L);
    while (lua_next(L, children) != 0) {
      CollectUiHandlers(L, -1, counter);
      lua_pop(L, 1);
    }
  }
  lua_pop(L, 1);
}

void UnrefUiIds(lua_State* L, const std::string& json) {
  std::optional<base::Value> parsed = base::JSONReader::Read(json, base::JSON_PARSE_RFC);
  if (!parsed || !parsed->is_dict()) {
    return;
  }
  const base::ListValue* ids = parsed->GetDict().FindList("ids");
  if (!ids) {
    return;
  }
  for (const auto& item : *ids) {
    if (!item.is_string()) {
      continue;
    }
    UnrefUiClick(L, item.GetString());
    UnrefUiClick(L, item.GetString() + ":change");
    UnrefUiClick(L, item.GetString() + ":long");
  }
}

int LuaUiRender(lua_State* L) {
  const char* slot = luaL_checkstring(L, 1);
  luaL_checktype(L, 2, LUA_TTABLE);
  int counter = 0;
  CollectUiHandlers(L, 2, &counter);
  base::DictValue dict;
  dict.Set("slot", slot);
  dict.Set("tree", LuaToValue(L, 2));
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "render", json);
}

int LuaUiUnmount(lua_State* L) {
  const char* slot = luaL_optstring(L, 1, "*");
  base::DictValue dict;
  dict.Set("slot", slot);
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string result = JavaString(
      env, Java_LemurXBridge_uiOp(
               env, base::android::ConvertUTF8ToJavaString(env, "unmount"),
               base::android::ConvertUTF8ToJavaString(env, json)));
  UnrefUiIds(L, result);
  return PushJson(L, result);
}

int LuaUiStyle(lua_State* L) {
  base::DictValue dict;
  if (lua_istable(L, 1)) {
    dict.Set("query", LuaToValue(L, 1));
  } else if (lua_isstring(L, 1)) {
    base::DictValue query;
    query.Set("text", lua_tostring(L, 1));
    dict.Set("query", std::move(query));
  }
  if (lua_istable(L, 2)) {
    dict.Set("style", LuaToValue(L, 2));
  }
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "style", json);
}

int LuaUiSlots(lua_State* L) { return CallUiOp(L, "slots", "{}"); }

// query 参数：table 直接用；字符串当 text 匹配
base::DictValue QueryArg(lua_State* L, int index) {
  base::DictValue query;
  if (lua_istable(L, index)) {
    base::Value v = LuaToValue(L, index);
    if (v.is_dict()) {
      query = std::move(v.GetDict());
    }
  } else if (lua_isstring(L, index)) {
    query.Set("id", lua_tostring(L, index));
  }
  return query;
}

// lemurx.ui.replace(query, tree[, opts]) / lemurx.ui.insert(query, tree[, opts])
int LuaUiSurgery(lua_State* L, const char* action) {
  luaL_checktype(L, 2, LUA_TTABLE);
  int counter = 0;
  CollectUiHandlers(L, 2, &counter);
  base::DictValue dict;
  dict.Set("query", QueryArg(L, 1));
  dict.Set("tree", LuaToValue(L, 2));
  if (lua_istable(L, 3)) {
    dict.Set("opts", LuaToValue(L, 3));
  }
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, action, json);
}

int LuaUiReplace(lua_State* L) { return LuaUiSurgery(L, "replace"); }
int LuaUiInsert(lua_State* L) { return LuaUiSurgery(L, "insert"); }

int LuaUiQueryOp(lua_State* L, const char* action) {
  base::DictValue dict;
  if (lua_istable(L, 1) || lua_isstring(L, 1)) {
    dict.Set("query", QueryArg(L, 1));
  }
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, action, json);
}

int LuaUiDetach(lua_State* L) { return LuaUiQueryOp(L, "detach"); }
int LuaUiRestore(lua_State* L) { return LuaUiQueryOp(L, "restore"); }
int LuaUiChildren(lua_State* L) { return LuaUiQueryOp(L, "children"); }

// lemurx.ui.move(query, {parent=query, index=n, before=query, after=query, width, height, weight})
int LuaUiMove(lua_State* L) {
  base::DictValue dict;
  if (lua_istable(L, 2)) {
    base::Value v = LuaToValue(L, 2);
    if (v.is_dict()) {
      dict = std::move(v.GetDict());
    }
  }
  dict.Set("query", QueryArg(L, 1));
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "move", json);
}

int g_ui_on_counter = 0;

// lemurx.ui.on(query, "click"|"longclick"|"touch"|"focus"|"text", fn[, {consume=, move=, max=}])
// 返回 {ok, count, key}，lemurx.ui.off(key) 解除。
int LuaUiOn(lua_State* L) {
  const char* event = luaL_checkstring(L, 2);
  luaL_checktype(L, 3, LUA_TFUNCTION);
  std::string key = "on:" + std::to_string(++g_ui_on_counter);
  lua_pushvalue(L, 3);
  int ref = luaL_ref(L, LUA_REGISTRYINDEX);
  UnrefUiClick(L, key);
  g_ui_click_refs[key] = MakeCb(L, ref);
  base::DictValue dict;
  dict.Set("query", QueryArg(L, 1));
  dict.Set("event", event);
  dict.Set("key", key);
  if (lua_istable(L, 4)) {
    dict.Set("opts", LuaToValue(L, 4));
  }
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "on", json);
}

int LuaUiOff(lua_State* L) {
  const char* key = luaL_optstring(L, 1, "*");
  if (strcmp(key, "*") == 0) {
    std::vector<std::string> keys;
    for (const auto& item : g_ui_click_refs) {
      if (item.first.rfind("on:", 0) == 0) {
        keys.push_back(item.first);
      }
    }
    for (const auto& k : keys) {
      UnrefUiClick(L, k);
    }
  } else {
    UnrefUiClick(L, key);
  }
  base::DictValue dict;
  dict.Set("key", key);
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "off", json);
}

int LuaUiShell(lua_State* L) { return CallUiOp(L, "shell", "{}"); }

int LuaThemeSet(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  return CallUiOp(L, "theme_set", LuaToJson(L, 1));
}

int LuaThemeGet(lua_State* L) { return CallUiOp(L, "theme_get", "{}"); }

int LuaThemeReset(lua_State* L) { return CallUiOp(L, "theme_reset", "{}"); }

int LuaUiDump(lua_State* L) { return LuaUiOp(L, "dump"); }
int LuaUiFind(lua_State* L) { return LuaUiOp(L, "find"); }
int LuaUiClick(lua_State* L) { return LuaUiOp(L, "click"); }
int LuaUiLongClick(lua_State* L) { return LuaUiOp(L, "longclick"); }
int LuaUiSetText(lua_State* L) { return LuaUiOp(L, "settext"); }
int LuaUiGetText(lua_State* L) { return LuaUiOp(L, "gettext"); }
int LuaUiVisible(lua_State* L) { return LuaUiOp(L, "visible"); }
int LuaUiEnabled(lua_State* L) { return LuaUiOp(L, "enabled"); }

int LuaUiDialog(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  int ok_ref = LUA_NOREF;
  int cancel_ref = LUA_NOREF;
  lua_getfield(L, 1, "onOk");
  if (lua_isfunction(L, -1)) {
    ok_ref = luaL_ref(L, LUA_REGISTRYINDEX);
  } else {
    lua_pop(L, 1);
  }
  lua_getfield(L, 1, "onCancel");
  if (lua_isfunction(L, -1)) {
    cancel_ref = luaL_ref(L, LUA_REGISTRYINDEX);
  } else {
    lua_pop(L, 1);
  }
  std::string options = LuaToJson(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string json = JavaString(
      env, Java_LemurXBridge_uiOp(
               env, base::android::ConvertUTF8ToJavaString(env, "dialog"),
               base::android::ConvertUTF8ToJavaString(env, options)));
  std::string id;
  std::optional<base::Value> parsed = base::JSONReader::Read(json, base::JSON_PARSE_RFC);
  if (parsed && parsed->is_dict()) {
    if (const std::string* value = parsed->GetDict().FindString("id")) {
      id = *value;
    }
  }
  if (!id.empty() && ok_ref != LUA_NOREF) {
    std::string key = std::string("dialog:") + id + ":ok";
    UnrefUiClick(L, key);
    g_ui_click_refs[key] =
        MakeCb(L, ok_ref);
  } else if (ok_ref != LUA_NOREF) {
    luaL_unref(L, LUA_REGISTRYINDEX, ok_ref);
  }
  if (!id.empty() && cancel_ref != LUA_NOREF) {
    std::string key = std::string("dialog:") + id + ":cancel";
    UnrefUiClick(L, key);
    g_ui_click_refs[key] =
        MakeCb(L, cancel_ref);
  } else if (cancel_ref != LUA_NOREF) {
    luaL_unref(L, LUA_REGISTRYINDEX, cancel_ref);
  }
  return PushJson(L, json);
}

int LuaDbExec(lua_State* L) {
  const char* sql = luaL_checkstring(L, 1);
  std::string args = "[]";
  if (lua_istable(L, 2)) {
    args = LuaToJson(L, 2);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_dbExec(
                                         env,
                                         base::android::ConvertUTF8ToJavaString(
                                             env, sql),
                                         base::android::ConvertUTF8ToJavaString(
                                             env, args))));
}

int LuaDbQuery(lua_State* L) {
  const char* sql = luaL_checkstring(L, 1);
  std::string args = "[]";
  if (lua_istable(L, 2)) {
    args = LuaToJson(L, 2);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_dbQuery(
                                         env,
                                         base::android::ConvertUTF8ToJavaString(
                                             env, sql),
                                         base::android::ConvertUTF8ToJavaString(
                                             env, args))));
}

int LuaIntentStart(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  std::string options = LuaToJson(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_startActivity(
             env, base::android::ConvertUTF8ToJavaString(env, options),
             LemurXEngine::Get()->privileged()));
  return 1;
}

int LuaIntentBroadcast(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  std::string options = LuaToJson(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_sendBroadcast(
             env, base::android::ConvertUTF8ToJavaString(env, options),
             LemurXEngine::Get()->privileged()));
  return 1;
}

int LuaTabsOn(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  luaL_checktype(L, 2, LUA_TFUNCTION);
  LuaCb cb = MakeCb(L, luaL_ref(L, LUA_REGISTRYINDEX));
  g_tab_events[name].push_back(cb);
  return 0;
}

int LuaTimerStart(lua_State* L, bool repeat) {
  int delay_ms = luaL_checkinteger(L, 1);
  luaL_checktype(L, 2, LUA_TFUNCTION);
  if (delay_ms < 10) {
    delay_ms = 10;
  }
  TimerState timer;
  timer.cb = MakeCb(L, luaL_ref(L, LUA_REGISTRYINDEX));
  timer.interval_ms = repeat ? delay_ms : 0;
  int id = g_next_timer_id++;
  g_timers[id] = timer;
  ScheduleTimer(id, delay_ms);
  lua_pushinteger(L, id);
  return 1;
}

int LuaTimerAfter(lua_State* L) {
  return LuaTimerStart(L, false);
}

int LuaTimerEvery(lua_State* L) {
  return LuaTimerStart(L, true);
}

int LuaTimerCancel(lua_State* L) {
  int id = luaL_checkinteger(L, 1);
  auto it = g_timers.find(id);
  if (it == g_timers.end()) {
    lua_pushboolean(L, false);
    return 1;
  }
  it->second.cancelled = true;
  lua_pushboolean(L, true);
  return 1;
}

int LuaCookieGet(lua_State* L) {
  RequirePrivilege(L, "lemurx.cookie.get");
  const char* url = luaL_checkstring(L, 1);
  return PushJson(L, LemurXGetCookiesJson(url));
}

int LuaCookieSet(lua_State* L) {
  RequirePrivilege(L, "lemurx.cookie.set");
  const char* url = luaL_checkstring(L, 1);
  const char* line = luaL_checkstring(L, 2);
  lua_pushboolean(L, LemurXSetCookie(url, line));
  return 1;
}

int LuaHistoryQuery(lua_State* L) {
  RequirePrivilege(L, "lemurx.history.query");
  const char* query = luaL_optstring(L, 1, "");
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_queryHistory(
                                         env,
                                         base::android::ConvertUTF8ToJavaString(
                                             env, query))));
}

int LuaDownloadsList(lua_State* L) {
  RequirePrivilege(L, "lemurx.downloads.list");
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_listDownloads(env)));
}

int LuaDownloadsEnqueue(lua_State* L) {
  RequirePrivilege(L, "lemurx.downloads.enqueue");
  const char* url = luaL_checkstring(L, 1);
  jint tab_id = 0;
  if (lua_isinteger(L, 2)) {
    tab_id = static_cast<jint>(lua_tointeger(L, 2));
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_enqueueDownload(
             env, base::android::ConvertUTF8ToJavaString(env, url), tab_id));
  return 1;
}

int LuaIsPrivileged(lua_State* L) {
  lua_pushboolean(L, LemurXEngine::Get()->privileged());
  return 1;
}

int LuaScriptName(lua_State* L) {
  std::string name = LemurXEngine::Get()->NameOf(L);
  lua_pushlstring(L, name.data(), name.size());
  return 1;
}

// ===== 跨状态桥：本地 lemurx.expose(name, fn|table) → UGC lemurx.import(name) =====
// 本地脚本和 UGC 不共享 lua_State，想给 UGC 用的工具函数得显式暴露。
// 参数/返回值按 JSON 编组（nil/bool/number/string/table），函数不能跨界传递。
// 被暴露的函数以本地特权执行——这是暴露者的选择。
std::map<std::string, LuaCb> g_exposed;

int LuaExpose(lua_State* L) {
  RequirePrivilege(L, "lemurx.expose");
  const char* name = luaL_checkstring(L, 1);
  luaL_checkany(L, 2);
  auto it = g_exposed.find(name);
  if (it != g_exposed.end()) {
    luaL_unref(it->second.L, LUA_REGISTRYINDEX, it->second.ref);
    g_exposed.erase(it);
  }
  lua_pushvalue(L, 2);
  int ref = luaL_ref(L, LUA_REGISTRYINDEX);
  g_exposed[name] = LuaCb{L, ref, true};
  return 0;
}

// 闭包上值：1 = expose 名，2 = 表内键（空串表示暴露的就是函数本身）
int LuaImportedCall(lua_State* L) {
  std::string name = lua_tostring(L, lua_upvalueindex(1));
  std::string key = lua_tostring(L, lua_upvalueindex(2));
  auto it = g_exposed.find(name);
  if (it == g_exposed.end() || !it->second.L) {
    return luaL_error(L, "lemurx.import: '%s' is no longer exposed", name.c_str());
  }
  lua_State* ML = it->second.L;
  int nargs = lua_gettop(L);
  base::ListValue args;
  for (int i = 1; i <= nargs; ++i) {
    args.Append(LuaToValue(L, i));
  }
  base::ListValue results;
  std::string error;
  {
    // 作用域必须在 luaL_error 之前结束：Lua 用 longjmp，跳过 C++ 析构
    LemurXEngine::ScopedState scope(LemurXEngine::Get(), ML, true);
    int base = lua_gettop(ML);
    lua_rawgeti(ML, LUA_REGISTRYINDEX, it->second.ref);
    if (!key.empty()) {
      if (lua_istable(ML, -1)) {
        lua_getfield(ML, -1, key.c_str());
        lua_remove(ML, -2);
      }
    }
    if (!lua_isfunction(ML, -1)) {
      lua_settop(ML, base);
      error = "not a function";
    } else {
      for (const auto& arg : args) {
        PushValue(ML, arg);
      }
      if (lua_pcall(ML, nargs, LUA_MULTRET, 0) != LUA_OK) {
        error = lua_tostring(ML, -1) ? lua_tostring(ML, -1) : "error";
        lua_settop(ML, base);
      } else {
        int nret = lua_gettop(ML) - base;
        for (int i = 1; i <= nret; ++i) {
          results.Append(LuaToValue(ML, base + i));
        }
        lua_settop(ML, base);
      }
    }
  }
  if (!error.empty()) {
    return luaL_error(L, "lemurx.import '%s%s%s': %s", name.c_str(),
                      key.empty() ? "" : ".", key.c_str(), error.c_str());
  }
  for (const auto& value : results) {
    PushValue(L, value);
  }
  return static_cast<int>(results.size());
}

void PushImportedFunction(lua_State* L, const std::string& name,
                          const std::string& key) {
  lua_pushstring(L, name.c_str());
  lua_pushstring(L, key.c_str());
  lua_pushcclosure(L, LuaImportedCall, 2);
}

int LuaImport(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  auto it = g_exposed.find(name);
  if (it == g_exposed.end() || !it->second.L) {
    lua_pushnil(L);
    return 1;
  }
  lua_State* ML = it->second.L;
  if (ML == L) {
    // 同一个状态里直接给原值
    lua_rawgeti(L, LUA_REGISTRYINDEX, it->second.ref);
    return 1;
  }
  int base = lua_gettop(ML);
  lua_rawgeti(ML, LUA_REGISTRYINDEX, it->second.ref);
  if (lua_isfunction(ML, -1)) {
    lua_settop(ML, base);
    PushImportedFunction(L, name, "");
    return 1;
  }
  if (!lua_istable(ML, -1)) {
    base::Value value = LuaToValue(ML, lua_gettop(ML));
    lua_settop(ML, base);
    PushValue(L, value);
    return 1;
  }
  // 表：函数成员变成跨界闭包，其余成员按值拷贝
  lua_newtable(L);
  lua_pushnil(ML);
  while (lua_next(ML, base + 1) != 0) {
    if (lua_type(ML, -2) == LUA_TSTRING) {
      std::string key = lua_tostring(ML, -2);
      if (lua_isfunction(ML, -1)) {
        PushImportedFunction(L, name, key);
      } else {
        PushValue(L, LuaToValue(ML, lua_gettop(ML)));
      }
      lua_setfield(L, -2, key.c_str());
    }
    lua_pop(ML, 1);
  }
  lua_settop(ML, base);
  return 1;
}

int LuaExposed(lua_State* L) {
  lua_newtable(L);
  lua_Integer i = 1;
  for (const auto& item : g_exposed) {
    lua_pushstring(L, item.first.c_str());
    lua_rawseti(L, -2, i++);
  }
  return 1;
}

int LuaChromeControls(lua_State* L) {
  const char* state = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string result = JavaString(
      env, Java_LemurXBridge_chromeSetControls(
               env, base::android::ConvertUTF8ToJavaString(env, state)));
  lua_pushlstring(L, result.data(), result.size());
  return 1;
}

int LuaChromeHideUrlBar(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_chromeHideUrlBar(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeSetUrlBarText(lua_State* L) {
  const char* text = luaL_optstring(L, 1, "");
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetUrlBarText(
             env, base::android::ConvertUTF8ToJavaString(env, text)));
  return 1;
}

int LuaChromeGetUrlBarText(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string text =
      JavaString(env, Java_LemurXBridge_chromeGetUrlBarText(env));
  lua_pushlstring(L, text.data(), text.size());
  return 1;
}

int LuaChromeFocusUrlBar(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeFocusUrlBar(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeSetToolbarColor(lua_State* L) {
  const char* color = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetToolbarColor(
             env, base::android::ConvertUTF8ToJavaString(env, color)));
  return 1;
}

int LuaChromeSetStatusBarColor(lua_State* L) {
  const char* color = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetStatusBarColor(
             env, base::android::ConvertUTF8ToJavaString(env, color)));
  return 1;
}

int LuaChromeSetDarkMode(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetDarkMode(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeIsDarkMode(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_chromeIsDarkMode(env));
  return 1;
}

int LuaChromeSetFullscreen(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetFullscreen(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeSetPullRefresh(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetPullRefresh(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeSetMenuButtonVisible(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeSetMenuButtonVisible(env,
                                                       lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeHideBottomToolbar(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeHideBottomToolbar(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaChromeHideButton(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeHideButton(
             env, base::android::ConvertUTF8ToJavaString(env, name),
             lua_toboolean(L, 2)));
  return 1;
}

int LuaChromeBack(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_chromeBack(env));
  return 1;
}

int LuaChromeForward(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_chromeForward(env));
  return 1;
}

int LuaChromeInfo(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_chromeInfo(env)));
}

int LuaChromeOn(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  luaL_checktype(L, 2, LUA_TFUNCTION);
  LuaCb cb = MakeCb(L, luaL_ref(L, LUA_REGISTRYINDEX));
  g_tab_events[name].push_back(cb);
  if (strcmp(name, "back") == 0) {
    JNIEnv* env = base::android::AttachCurrentThread();
    Java_LemurXBridge_chromeSetBackIntercept(env, true);
  }
  return 0;
}

int LuaMenuAdd(lua_State* L) {
  luaL_checktype(L, 1, LUA_TTABLE);
  lua_getfield(L, 1, "onClick");
  int click_ref = LUA_NOREF;
  if (lua_isfunction(L, -1)) {
    click_ref = luaL_ref(L, LUA_REGISTRYINDEX);
  } else {
    lua_pop(L, 1);
  }
  std::string options = LuaToJson(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string menu_id = JavaString(
      env, Java_LemurXBridge_chromeMenuAdd(
               env, base::android::ConvertUTF8ToJavaString(env, options)));
  if (menu_id.empty()) {
    if (click_ref != LUA_NOREF) {
      luaL_unref(L, LUA_REGISTRYINDEX, click_ref);
    }
    lua_pushnil(L);
    return 1;
  }
  UnrefUiClick(L, std::string("menu:") + menu_id);
  if (click_ref != LUA_NOREF) {
    g_ui_click_refs[std::string("menu:") + menu_id] =
        MakeCb(L, click_ref);
  }
  lua_pushlstring(L, menu_id.data(), menu_id.size());
  return 1;
}

int LuaMenuRemove(lua_State* L) {
  const char* id = luaL_checkstring(L, 1);
  UnrefUiClick(L, std::string("menu:") + id);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeMenuRemove(
             env, base::android::ConvertUTF8ToJavaString(env, id)));
  return 1;
}

int LuaMenuClear(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_chromeMenuClear(env);
  return 0;
}

int LuaMenuHide(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  int hidden = 1;
  if (lua_gettop(L) >= 2) {
    hidden = lua_toboolean(L, 2);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeMenuHide(
             env, base::android::ConvertUTF8ToJavaString(env, name), hidden));
  return 1;
}

int LuaMenuOn(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  luaL_checktype(L, 2, LUA_TFUNCTION);
  LuaCb cb = MakeCb(L, luaL_ref(L, LUA_REGISTRYINDEX));
  std::string event = std::string("menu:") + name;
  g_tab_events[event].push_back(cb);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_chromeMenuIntercept(
      env, base::android::ConvertUTF8ToJavaString(env, name), true);
  return 0;
}

int LuaMenuInvoke(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_chromeMenuInvoke(
             env, base::android::ConvertUTF8ToJavaString(env, name)));
  return 1;
}

int LuaMenuList(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_chromeMenuList(env)));
}

int LuaInputOnBack(lua_State* L) {
  luaL_checktype(L, 1, LUA_TFUNCTION);
  LuaCb cb = MakeCb(L, luaL_ref(L, LUA_REGISTRYINDEX));
  g_tab_events["back"].push_back(cb);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_chromeSetBackIntercept(env, true);
  return 0;
}

int LuaInputInterceptBack(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_chromeSetBackIntercept(env, lua_toboolean(L, 1));
  return 0;
}

std::map<int, LuaCb> g_http_callbacks;
int g_next_http_id = 1;

void DispatchHttpResultOnLuaThread(int id, std::string json) {
  auto it = g_http_callbacks.find(id);
  if (it == g_http_callbacks.end()) {
    return;
  }
  LuaCb cb = it->second;
  g_http_callbacks.erase(it);
  lua_State* L = cb.L;
  if (!L) {
    return;
  }
  LemurXEngine::ScopedState scope(LemurXEngine::Get(), L, cb.privileged);
  lua_rawgeti(L, LUA_REGISTRYINDEX, cb.ref);
  PushJson(L, json);
  if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "http cb";
    lua_pop(L, 1);
    LOG(ERROR) << "LemurX http callback: " << err;
  }
  luaL_unref(L, LUA_REGISTRYINDEX, cb.ref);
}

// lemurx.http.fetch(url[, opts][, callback])
// 有回调即异步：立刻返回请求 id，结果稍后在 Lua 线程回调；没有回调则同步阻塞。
int LuaHttpFetch(lua_State* L) {
  const char* url = luaL_checkstring(L, 1);
  std::string options = "{}";
  int cb_index = 0;
  if (lua_istable(L, 2)) {
    options = LuaToJson(L, 2);
  }
  // 最后一个参数是函数就当回调（允许 fetch(url, nil, cb)）
  int top = lua_gettop(L);
  if (top >= 2 && lua_isfunction(L, top)) {
    cb_index = top;
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  if (cb_index) {
    lua_pushvalue(L, cb_index);
    int ref = luaL_ref(L, LUA_REGISTRYINDEX);
    int id = g_next_http_id++;
    g_http_callbacks[id] = MakeCb(L, ref);
    Java_LemurXBridge_httpFetchAsync(
        env, base::android::ConvertUTF8ToJavaString(env, url),
        base::android::ConvertUTF8ToJavaString(env, options),
        LemurXEngine::Get()->privileged(), id);
    lua_pushinteger(L, id);
    return 1;
  }
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_httpFetch(
                             env, base::android::ConvertUTF8ToJavaString(env, url),
                             base::android::ConvertUTF8ToJavaString(env, options),
                             LemurXEngine::Get()->privileged())));
}

int LuaFsRoot(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string root = JavaString(
      env, Java_LemurXBridge_fsRoot(env, LemurXEngine::Get()->privileged()));
  lua_pushlstring(L, root.data(), root.size());
  return 1;
}

int LuaFsRead(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  std::string options = "{}";
  if (lua_istable(L, 2)) {
    options = LuaToJson(L, 2);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_fsRead(
                             env, base::android::ConvertUTF8ToJavaString(env, path),
                             base::android::ConvertUTF8ToJavaString(env, options),
                             LemurXEngine::Get()->privileged())));
}

int LuaFsWrite(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  const char* data = luaL_optstring(L, 2, "");
  std::string options = "{}";
  if (lua_istable(L, 3)) {
    options = LuaToJson(L, 3);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_fsWrite(
             env, base::android::ConvertUTF8ToJavaString(env, path),
             base::android::ConvertUTF8ToJavaString(env, data),
             base::android::ConvertUTF8ToJavaString(env, options),
             LemurXEngine::Get()->privileged()));
  return 1;
}

int LuaFsList(lua_State* L) {
  const char* path = luaL_optstring(L, 1, "");
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_fsList(
                             env, base::android::ConvertUTF8ToJavaString(env, path),
                             LemurXEngine::Get()->privileged())));
}

int LuaFsExists(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_fsExists(
             env, base::android::ConvertUTF8ToJavaString(env, path),
             LemurXEngine::Get()->privileged()));
  return 1;
}

int LuaFsMkdir(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_fsMkdir(
             env, base::android::ConvertUTF8ToJavaString(env, path),
             LemurXEngine::Get()->privileged()));
  return 1;
}

int LuaFsRemove(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_fsRemove(
             env, base::android::ConvertUTF8ToJavaString(env, path),
             LemurXEngine::Get()->privileged()));
  return 1;
}

int LuaTabsScreenshot(lua_State* L) {
  jint tab_id = 0;
  std::string options = "{}";
  if (lua_istable(L, 1)) {
    options = LuaToJson(L, 1);
  } else {
    tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
    if (lua_istable(L, 2)) {
      options = LuaToJson(L, 2);
    }
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_tabScreenshot(
                             env, tab_id,
                             base::android::ConvertUTF8ToJavaString(env, options),
                             LemurXEngine::Get()->privileged())));
}

int LuaTabsMute(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabMute(env, tab_id, lua_toboolean(L, 2)));
  return 1;
}

int LuaTabsIsMuted(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabIsMuted(env, tab_id));
  return 1;
}

int LuaTabsStop(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabStop(env, tab_id));
  return 1;
}

int LuaTabsHtml(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_tabHtml(env, tab_id)));
}

int LuaTabsSetUserAgent(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  const char* ua = luaL_optstring(L, 2, "");
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_tabSetUserAgent(
             env, tab_id, base::android::ConvertUTF8ToJavaString(env, ua)));
  return 1;
}

int LuaTabsSetHeaders(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  std::string headers = "{}";
  if (lua_istable(L, 2)) {
    headers = LuaToJson(L, 2);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_tabSetHeaders(
             env, tab_id, base::android::ConvertUTF8ToJavaString(env, headers)));
  return 1;
}

int LuaInputTap(lua_State* L) {
  std::string options;
  if (lua_istable(L, 1)) {
    options = LuaToJson(L, 1);
  } else {
    base::DictValue dict;
    dict.Set("x", luaL_checknumber(L, 1));
    dict.Set("y", luaL_checknumber(L, 2));
    if (lua_isnumber(L, 3)) {
      dict.Set("tab", static_cast<int>(lua_tointeger(L, 3)));
    }
    if (lua_isstring(L, 4)) {
      dict.Set("unit", lua_tostring(L, 4));
    }
    base::JSONWriter::Write(base::Value(std::move(dict)), &options);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_inputTap(
             env, base::android::ConvertUTF8ToJavaString(env, options)));
  return 1;
}

int LuaInputSwipe(lua_State* L) {
  std::string options;
  if (lua_istable(L, 1)) {
    options = LuaToJson(L, 1);
  } else {
    base::DictValue dict;
    dict.Set("x1", luaL_checknumber(L, 1));
    dict.Set("y1", luaL_checknumber(L, 2));
    dict.Set("x2", luaL_checknumber(L, 3));
    dict.Set("y2", luaL_checknumber(L, 4));
    if (lua_isnumber(L, 5)) {
      dict.Set("duration", static_cast<int>(lua_tointeger(L, 5)));
    }
    if (lua_isnumber(L, 6)) {
      dict.Set("tab", static_cast<int>(lua_tointeger(L, 6)));
    }
    base::JSONWriter::Write(base::Value(std::move(dict)), &options);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_inputSwipe(
             env, base::android::ConvertUTF8ToJavaString(env, options)));
  return 1;
}

int LuaInputType(lua_State* L) {
  const char* text = luaL_checkstring(L, 1);
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 2, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_inputType(
             env, base::android::ConvertUTF8ToJavaString(env, text), tab_id));
  return 1;
}

int LuaInputKey(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 2, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_inputKey(
             env, base::android::ConvertUTF8ToJavaString(env, name), tab_id));
  return 1;
}

int LuaPermSet(lua_State* L) {
  RequirePrivilege(L, "lemurx.perm.set");
  const char* origin = luaL_checkstring(L, 1);
  const char* type = luaL_checkstring(L, 2);
  const char* value = luaL_checkstring(L, 3);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_permSet(
             env, base::android::ConvertUTF8ToJavaString(env, origin),
             base::android::ConvertUTF8ToJavaString(env, type),
             base::android::ConvertUTF8ToJavaString(env, value)));
  return 1;
}

int LuaPermGet(lua_State* L) {
  RequirePrivilege(L, "lemurx.perm.get");
  const char* origin = luaL_checkstring(L, 1);
  const char* type = luaL_checkstring(L, 2);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string value = JavaString(
      env, Java_LemurXBridge_permGet(
               env, base::android::ConvertUTF8ToJavaString(env, origin),
               base::android::ConvertUTF8ToJavaString(env, type)));
  lua_pushlstring(L, value.data(), value.size());
  return 1;
}

int LuaCdpSend(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.send");
  jint tab_id = 0;
  const char* method = nullptr;
  std::string params = "{}";
  int timeout = 8000;
  int method_index = 1;
  if (lua_type(L, 1) == LUA_TNUMBER) {
    tab_id = static_cast<jint>(lua_tointeger(L, 1));
    method_index = 2;
  }
  method = luaL_checkstring(L, method_index);
  if (lua_istable(L, method_index + 1)) {
    params = LuaToJson(L, method_index + 1);
  } else if (lua_isstring(L, method_index + 1)) {
    params = lua_tostring(L, method_index + 1);
  }
  if (lua_istable(L, method_index + 2)) {
    lua_getfield(L, method_index + 2, "timeout");
    if (lua_isnumber(L, -1)) {
      timeout = static_cast<int>(lua_tointeger(L, -1));
    }
    lua_pop(L, 1);
    lua_getfield(L, method_index + 2, "sessionId");
    if (lua_isstring(L, -1)) {
      std::optional<base::Value> parsed = base::JSONReader::Read(params, base::JSON_PARSE_RFC);
      base::DictValue dict;
      if (parsed && parsed->is_dict()) {
        dict = std::move(parsed->GetDict());
      }
      dict.Set("sessionId", lua_tostring(L, -1));
      base::JSONWriter::Write(base::Value(std::move(dict)), &params);
    }
    lua_pop(L, 1);
  } else if (lua_isnumber(L, method_index + 2)) {
    timeout = static_cast<int>(lua_tointeger(L, method_index + 2));
  }
  return PushJson(L, LemurXCdpSend(tab_id, method, params, timeout));
}

int LuaCdpAttach(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.attach");
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  lua_pushboolean(L, LemurXCdpAttach(tab_id));
  return 1;
}

int LuaCdpDetach(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.detach");
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  LemurXCdpDetach(tab_id);
  return 0;
}

int LuaCdpVersion(lua_State* L) {
  std::string version = LemurXCdpVersion();
  lua_pushlstring(L, version.data(), version.size());
  return 1;
}

int LuaCdpInspect(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.inspect");
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  int x = static_cast<int>(luaL_checknumber(L, 2));
  int y = static_cast<int>(luaL_checknumber(L, 3));
  lua_pushboolean(L, LemurXCdpInspect(tab_id, x, y));
  return 1;
}

int LuaCdpOn(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.on");
  const char* name = luaL_checkstring(L, 1);
  luaL_checktype(L, 2, LUA_TFUNCTION);
  LuaCb cb = MakeCb(L, luaL_ref(L, LUA_REGISTRYINDEX));
  g_cdp_events[name].push_back(cb);
  return 0;
}

int LuaCdpTargets(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.targets");
  return PushJson(L, LemurXCdpTargets());
}

int LuaCdpHost(lua_State* L) {
  RequirePrivilege(L, "lemurx.cdp.host");
  const char* host_id = luaL_checkstring(L, 1);
  const char* method = luaL_checkstring(L, 2);
  std::string params = "{}";
  int timeout = 8000;
  if (lua_istable(L, 3)) {
    params = LuaToJson(L, 3);
  } else if (lua_isstring(L, 3)) {
    params = lua_tostring(L, 3);
  }
  if (lua_istable(L, 4)) {
    lua_getfield(L, 4, "timeout");
    if (lua_isnumber(L, -1)) {
      timeout = static_cast<int>(lua_tointeger(L, -1));
    }
    lua_pop(L, 1);
    lua_getfield(L, 4, "sessionId");
    if (lua_isstring(L, -1)) {
      std::optional<base::Value> parsed = base::JSONReader::Read(params, base::JSON_PARSE_RFC);
      base::DictValue dict;
      if (parsed && parsed->is_dict()) {
        dict = std::move(parsed->GetDict());
      }
      dict.Set("sessionId", lua_tostring(L, -1));
      base::JSONWriter::Write(base::Value(std::move(dict)), &params);
    }
    lua_pop(L, 1);
  } else if (lua_isnumber(L, 4)) {
    timeout = static_cast<int>(lua_tointeger(L, 4));
  }
  return PushJson(L, LemurXCdpSendHost(host_id, method, params, timeout));
}

int LuaTabsNavHistory(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_tabNavHistory(env, tab_id)));
}

int LuaTabsNavGo(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  jint index = static_cast<jint>(luaL_checkinteger(L, 2));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabNavGo(env, tab_id, index));
  return 1;
}

int LuaTabsNavOffset(lua_State* L) {
  jint tab_id = 0;
  jint offset = 0;
  if (lua_gettop(L) >= 2) {
    tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
    offset = static_cast<jint>(luaL_checkinteger(L, 2));
  } else {
    offset = static_cast<jint>(luaL_checkinteger(L, 1));
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabNavOffset(env, tab_id, offset));
  return 1;
}

int LuaTabsReloadBypassCache(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabReloadBypassCache(env, tab_id));
  return 1;
}

int LuaTabsFrames(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_optinteger(L, 1, 0));
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(L, JavaString(env, Java_LemurXBridge_tabFrames(env, tab_id)));
}

int LuaPrefsGet(lua_State* L) {
  RequirePrivilege(L, "lemurx.prefs.get");
  const char* name = luaL_checkstring(L, 1);
  const char* type = luaL_optstring(L, 2, "");
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_prefsGet(
                             env, base::android::ConvertUTF8ToJavaString(env, name),
                             base::android::ConvertUTF8ToJavaString(env, type))));
}

int LuaPrefsSet(lua_State* L) {
  RequirePrivilege(L, "lemurx.prefs.set");
  const char* name = luaL_checkstring(L, 1);
  std::string type = "string";
  std::string value;
  int value_index = 2;
  if (lua_isstring(L, 3) || lua_isboolean(L, 3) || lua_isnumber(L, 3)) {
    type = luaL_optstring(L, 2, "string");
    value_index = 3;
  }
  if (lua_isboolean(L, value_index)) {
    type = "bool";
    value = lua_toboolean(L, value_index) ? "true" : "false";
  } else if (lua_isinteger(L, value_index)) {
    if (type != "long" && type != "double") {
      type = "int";
    }
    value = std::to_string(lua_tointeger(L, value_index));
  } else if (lua_isnumber(L, value_index)) {
    type = "double";
    value = std::to_string(lua_tonumber(L, value_index));
  } else {
    value = luaL_checkstring(L, value_index);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_prefsSet(
             env, base::android::ConvertUTF8ToJavaString(env, name),
             base::android::ConvertUTF8ToJavaString(env, type),
             base::android::ConvertUTF8ToJavaString(env, value)));
  return 1;
}

int LuaPrefsClear(lua_State* L) {
  RequirePrivilege(L, "lemurx.prefs.clear");
  const char* name = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_prefsClear(
             env, base::android::ConvertUTF8ToJavaString(env, name)));
  return 1;
}

int LuaDataClear(lua_State* L) {
  RequirePrivilege(L, "lemurx.data.clear");
  std::string types = "[]";
  std::string period = "hour";
  if (lua_istable(L, 1)) {
    types = LuaToJson(L, 1);
    period = luaL_optstring(L, 2, "hour");
  } else if (lua_isstring(L, 1)) {
    period = lua_tostring(L, 1);
    if (lua_istable(L, 2)) {
      types = LuaToJson(L, 2);
    }
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_dataClear(
                             env, base::android::ConvertUTF8ToJavaString(env, types),
                             base::android::ConvertUTF8ToJavaString(env, period))));
}

int LuaFeatureEnabled(lua_State* L) {
  const char* name = luaL_checkstring(L, 1);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_featureEnabled(
             env, base::android::ConvertUTF8ToJavaString(env, name)));
  return 1;
}

int LuaFeatureParam(lua_State* L) {
  const char* feature = luaL_checkstring(L, 1);
  const char* param = luaL_checkstring(L, 2);
  JNIEnv* env = base::android::AttachCurrentThread();
  std::string value = JavaString(
      env, Java_LemurXBridge_featureParam(
               env, base::android::ConvertUTF8ToJavaString(env, feature),
               base::android::ConvertUTF8ToJavaString(env, param)));
  lua_pushlstring(L, value.data(), value.size());
  return 1;
}

int LuaTabsSetDesktop(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_tabSetDesktop(env, tab_id, lua_toboolean(L, 2)));
  return 1;
}

int LuaTabsSetZoom(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  double percent = luaL_checknumber(L, 2);
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabSetZoom(env, tab_id, percent));
  return 1;
}

int LuaTabsGetZoom(lua_State* L) {
  jint tab_id = static_cast<jint>(luaL_checkinteger(L, 1));
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushnumber(L, Java_LemurXBridge_tabGetZoom(env, tab_id));
  return 1;
}

int LuaTabsSetJavaScript(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(
      L, Java_LemurXBridge_tabSetJavaScript(env, lua_toboolean(L, 1)));
  return 1;
}

int LuaTabsIsJavaScript(lua_State* L) {
  JNIEnv* env = base::android::AttachCurrentThread();
  lua_pushboolean(L, Java_LemurXBridge_tabIsJavaScript(env));
  return 1;
}

void SetCFunction(lua_State* L, const char* name, lua_CFunction fn) {
  lua_pushcfunction(L, fn);
  lua_setfield(L, -2, name);
}

}  // namespace

void RegisterLemurXApi(lua_State* L) {
  lua_pushcfunction(L, LuaLog);
  lua_setglobal(L, "print");

  lua_newtable(L);

  SetCFunction(L, "log", LuaLog);
  SetCFunction(L, "toast", LuaToast);

  lua_newtable(L);
  SetCFunction(L, "info", LuaBrowserInfo);
  lua_setfield(L, -2, "browser");

  lua_newtable(L);
  SetCFunction(L, "list", LuaTabsList);
  SetCFunction(L, "current", LuaTabsCurrent);
  SetCFunction(L, "open", LuaTabsOpen);
  SetCFunction(L, "close", LuaTabsClose);
  SetCFunction(L, "select", LuaTabsSelect);
  SetCFunction(L, "navigate", LuaTabsNavigate);
  SetCFunction(L, "reload", LuaTabsReload);
  SetCFunction(L, "back", LuaTabsBack);
  SetCFunction(L, "forward", LuaTabsForward);
  SetCFunction(L, "eval", LuaTabsEval);
  SetCFunction(L, "inject", LuaTabsInject);
  SetCFunction(L, "hide", LuaTabsHide);
  SetCFunction(L, "show", LuaTabsShow);
  SetCFunction(L, "freeze", LuaTabsFreeze);
  SetCFunction(L, "on", LuaTabsOn);
  SetCFunction(L, "setDesktop", LuaTabsSetDesktop);
  SetCFunction(L, "setZoom", LuaTabsSetZoom);
  SetCFunction(L, "getZoom", LuaTabsGetZoom);
  SetCFunction(L, "setJavaScript", LuaTabsSetJavaScript);
  SetCFunction(L, "isJavaScript", LuaTabsIsJavaScript);
  SetCFunction(L, "screenshot", LuaTabsScreenshot);
  SetCFunction(L, "mute", LuaTabsMute);
  SetCFunction(L, "isMuted", LuaTabsIsMuted);
  SetCFunction(L, "stop", LuaTabsStop);
  SetCFunction(L, "html", LuaTabsHtml);
  SetCFunction(L, "setUserAgent", LuaTabsSetUserAgent);
  SetCFunction(L, "setHeaders", LuaTabsSetHeaders);
  SetCFunction(L, "history", LuaTabsNavHistory);
  SetCFunction(L, "go", LuaTabsNavGo);
  SetCFunction(L, "offset", LuaTabsNavOffset);
  SetCFunction(L, "reloadBypassCache", LuaTabsReloadBypassCache);
  SetCFunction(L, "frames", LuaTabsFrames);
  lua_setfield(L, -2, "tabs");

  lua_newtable(L);
  SetCFunction(L, "addRule", LuaNetAddRule);
  SetCFunction(L, "removeRule", LuaNetRemoveRule);
  SetCFunction(L, "clearRules", LuaNetClearRules);
  SetCFunction(L, "listRules", LuaNetListRules);
  lua_setfield(L, -2, "net");

  lua_newtable(L);
  SetCFunction(L, "fetch", LuaHttpFetch);
  lua_setfield(L, -2, "http");

  lua_newtable(L);
  SetCFunction(L, "set", LuaClipboardSet);
  SetCFunction(L, "get", LuaClipboardGet);
  lua_setfield(L, -2, "clipboard");

  lua_newtable(L);
  SetCFunction(L, "get", LuaStorageGet);
  SetCFunction(L, "set", LuaStorageSet);
  SetCFunction(L, "delete", LuaStorageDelete);
  SetCFunction(L, "list", LuaStorageList);
  lua_setfield(L, -2, "storage");

  lua_newtable(L);
  SetCFunction(L, "show", LuaUiShow);
  SetCFunction(L, "remove", LuaUiRemove);
  SetCFunction(L, "clear", LuaUiClear);
  SetCFunction(L, "dump", LuaUiDump);
  SetCFunction(L, "find", LuaUiFind);
  SetCFunction(L, "click", LuaUiClick);
  SetCFunction(L, "longClick", LuaUiLongClick);
  SetCFunction(L, "setText", LuaUiSetText);
  SetCFunction(L, "getText", LuaUiGetText);
  SetCFunction(L, "visible", LuaUiVisible);
  SetCFunction(L, "enabled", LuaUiEnabled);
  SetCFunction(L, "dialog", LuaUiDialog);
  SetCFunction(L, "render", LuaUiRender);
  SetCFunction(L, "unmount", LuaUiUnmount);
  SetCFunction(L, "style", LuaUiStyle);
  SetCFunction(L, "slots", LuaUiSlots);
  SetCFunction(L, "replace", LuaUiReplace);
  SetCFunction(L, "insert", LuaUiInsert);
  SetCFunction(L, "detach", LuaUiDetach);
  SetCFunction(L, "restore", LuaUiRestore);
  SetCFunction(L, "move", LuaUiMove);
  SetCFunction(L, "children", LuaUiChildren);
  SetCFunction(L, "on", LuaUiOn);
  SetCFunction(L, "off", LuaUiOff);
  SetCFunction(L, "shell", LuaUiShell);
  lua_setfield(L, -2, "ui");

  lua_newtable(L);
  SetCFunction(L, "set", LuaThemeSet);
  SetCFunction(L, "get", LuaThemeGet);
  SetCFunction(L, "reset", LuaThemeReset);
  lua_setfield(L, -2, "theme");

  lua_newtable(L);
  SetCFunction(L, "exec", LuaDbExec);
  SetCFunction(L, "query", LuaDbQuery);
  lua_setfield(L, -2, "db");

  lua_newtable(L);
  SetCFunction(L, "startActivity", LuaIntentStart);
  SetCFunction(L, "sendBroadcast", LuaIntentBroadcast);
  lua_setfield(L, -2, "intent");

  lua_newtable(L);
  SetCFunction(L, "after", LuaTimerAfter);
  SetCFunction(L, "every", LuaTimerEvery);
  SetCFunction(L, "cancel", LuaTimerCancel);
  lua_setfield(L, -2, "timer");

  lua_newtable(L);
  SetCFunction(L, "get", LuaCookieGet);
  SetCFunction(L, "set", LuaCookieSet);
  lua_setfield(L, -2, "cookie");

  lua_newtable(L);
  SetCFunction(L, "query", LuaHistoryQuery);
  lua_setfield(L, -2, "history");

  lua_newtable(L);
  SetCFunction(L, "list", LuaDownloadsList);
  SetCFunction(L, "enqueue", LuaDownloadsEnqueue);
  lua_setfield(L, -2, "downloads");

  lua_newtable(L);
  SetCFunction(L, "controls", LuaChromeControls);
  SetCFunction(L, "hideUrlBar", LuaChromeHideUrlBar);
  SetCFunction(L, "setUrlBarText", LuaChromeSetUrlBarText);
  SetCFunction(L, "getUrlBarText", LuaChromeGetUrlBarText);
  SetCFunction(L, "focusUrlBar", LuaChromeFocusUrlBar);
  SetCFunction(L, "setToolbarColor", LuaChromeSetToolbarColor);
  SetCFunction(L, "setStatusBarColor", LuaChromeSetStatusBarColor);
  SetCFunction(L, "setDarkMode", LuaChromeSetDarkMode);
  SetCFunction(L, "isDarkMode", LuaChromeIsDarkMode);
  SetCFunction(L, "fullscreen", LuaChromeSetFullscreen);
  SetCFunction(L, "setPullRefresh", LuaChromeSetPullRefresh);
  SetCFunction(L, "setMenuButtonVisible", LuaChromeSetMenuButtonVisible);
  SetCFunction(L, "hideBottomToolbar", LuaChromeHideBottomToolbar);
  SetCFunction(L, "hideButton", LuaChromeHideButton);
  SetCFunction(L, "back", LuaChromeBack);
  SetCFunction(L, "forward", LuaChromeForward);
  SetCFunction(L, "info", LuaChromeInfo);
  SetCFunction(L, "on", LuaChromeOn);
  lua_setfield(L, -2, "chrome");

  lua_newtable(L);
  SetCFunction(L, "add", LuaMenuAdd);
  SetCFunction(L, "remove", LuaMenuRemove);
  SetCFunction(L, "clear", LuaMenuClear);
  SetCFunction(L, "hide", LuaMenuHide);
  SetCFunction(L, "on", LuaMenuOn);
  SetCFunction(L, "invoke", LuaMenuInvoke);
  SetCFunction(L, "list", LuaMenuList);
  lua_setfield(L, -2, "menu");

  lua_newtable(L);
  SetCFunction(L, "onBack", LuaInputOnBack);
  SetCFunction(L, "interceptBack", LuaInputInterceptBack);
  SetCFunction(L, "tap", LuaInputTap);
  SetCFunction(L, "swipe", LuaInputSwipe);
  SetCFunction(L, "type", LuaInputType);
  SetCFunction(L, "key", LuaInputKey);
  lua_setfield(L, -2, "input");

  lua_newtable(L);
  SetCFunction(L, "root", LuaFsRoot);
  SetCFunction(L, "read", LuaFsRead);
  SetCFunction(L, "write", LuaFsWrite);
  SetCFunction(L, "list", LuaFsList);
  SetCFunction(L, "exists", LuaFsExists);
  SetCFunction(L, "mkdir", LuaFsMkdir);
  SetCFunction(L, "remove", LuaFsRemove);
  lua_setfield(L, -2, "fs");

  lua_newtable(L);
  SetCFunction(L, "set", LuaPermSet);
  SetCFunction(L, "get", LuaPermGet);
  lua_setfield(L, -2, "perm");

  lua_newtable(L);
  SetCFunction(L, "send", LuaCdpSend);
  SetCFunction(L, "attach", LuaCdpAttach);
  SetCFunction(L, "detach", LuaCdpDetach);
  SetCFunction(L, "version", LuaCdpVersion);
  SetCFunction(L, "inspect", LuaCdpInspect);
  SetCFunction(L, "on", LuaCdpOn);
  SetCFunction(L, "targets", LuaCdpTargets);
  SetCFunction(L, "host", LuaCdpHost);
  lua_setfield(L, -2, "cdp");

  lua_newtable(L);
  SetCFunction(L, "get", LuaPrefsGet);
  SetCFunction(L, "set", LuaPrefsSet);
  SetCFunction(L, "clear", LuaPrefsClear);
  lua_setfield(L, -2, "prefs");

  lua_newtable(L);
  SetCFunction(L, "clear", LuaDataClear);
  lua_setfield(L, -2, "data");

  lua_newtable(L);
  SetCFunction(L, "enabled", LuaFeatureEnabled);
  SetCFunction(L, "param", LuaFeatureParam);
  lua_setfield(L, -2, "features");

  SetCFunction(L, "isPrivileged", LuaIsPrivileged);
  SetCFunction(L, "scriptName", LuaScriptName);
  SetCFunction(L, "expose", LuaExpose);
  SetCFunction(L, "import", LuaImport);
  SetCFunction(L, "exposed", LuaExposed);

  lua_setglobal(L, "lemurx");

  // luakit 兼容内核的原生原语只给主状态；UGC 状态没有 __luakit。
  if (LemurXEngine::Get()->IsMainState(L) ||
      LemurXEngine::Get()->state() == nullptr) {
    RegisterLemurXLuakitNative(L);
    RegisterLemurXLuakitWebview(L);
    RegisterLemurXLuakitScheme(L);
    RegisterLemurXLuakitWebHost(L);
  }
}

void LemurXDispatchUiClick(const std::string& overlay_id) {
  LemurXDispatchUiClick(overlay_id, "{}");
}

void LemurXDispatchUiClick(const std::string& overlay_id,
                             const std::string& json) {
  LemurXEngine::Get()->RunOnLuaThread(
      base::BindOnce(&DispatchUiClickOnLuaThread, overlay_id, json));
}

void LemurXDispatchTabEvent(const std::string& name, const std::string& json) {
  LemurXEngine::Get()->RunOnLuaThread(
      base::BindOnce(&DispatchTabEventOnLuaThread, name, json));
}

void LemurXDispatchCdpEvent(const std::string& name, const std::string& json) {
  LemurXEngine::Get()->RunOnLuaThread(
      base::BindOnce(&DispatchCdpEventOnLuaThread, name, json));
}

void LemurXDispatchHttpResult(int request_id, const std::string& json) {
  LemurXEngine::Get()->RunOnLuaThread(
      base::BindOnce(&DispatchHttpResultOnLuaThread, request_id, json));
}

std::string LemurXLuakitEnvJson() {
  JNIEnv* env = base::android::AttachCurrentThread();
  jni_zero::ScopedJavaLocalRef<jstring> js =
      Java_LemurXBridge_luakitEnv(env);
  if (js.is_null()) {
    return std::string();
  }
  return base::android::ConvertJavaStringToUTF8(env, js);
}

std::string LemurXLuakitWidgetOp(const std::string& op,
                                   int id,
                                   const std::string& json) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jni_zero::ScopedJavaLocalRef<jstring> js =
      Java_LemurXBridge_luakitWidget(
          env, base::android::ConvertUTF8ToJavaString(env, op), id,
          base::android::ConvertUTF8ToJavaString(env, json));
  if (js.is_null()) {
    return std::string();
  }
  return base::android::ConvertJavaStringToUTF8(env, js);
}

content::WebContents* LemurXWebContentsForTab(int tab_id) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jni_zero::ScopedJavaLocalRef<jobject> jwc =
      Java_LemurXBridge_webContentsForTab(env, tab_id);
  if (jwc.is_null()) {
    return nullptr;
  }
  return content::WebContents::FromJavaWebContents(jwc);
}

static void JNI_LemurXBridge_Start(JNIEnv* env) {
  LemurXEngine::Get()->Start();
}

static void JNI_LemurXBridge_Eval(
    JNIEnv* env,
    const jni_zero::JavaRef<jstring>& jchunk,
    const jni_zero::JavaRef<jstring>& jname,
    jboolean privileged) {
  LemurXEngine::Get()->Eval(
      base::android::ConvertJavaStringToUTF8(env, jchunk),
      base::android::ConvertJavaStringToUTF8(env, jname), privileged);
}

static void JNI_LemurXBridge_DispatchUiClick(
    JNIEnv* env,
    const jni_zero::JavaRef<jstring>& joverlay_id,
    const jni_zero::JavaRef<jstring>& jjson) {
  LemurXDispatchUiClick(
      base::android::ConvertJavaStringToUTF8(env, joverlay_id),
      jjson.is_null() ? "{}"
                      : base::android::ConvertJavaStringToUTF8(env, jjson));
}

static void JNI_LemurXBridge_DispatchTabEvent(
    JNIEnv* env,
    const jni_zero::JavaRef<jstring>& jname,
    const jni_zero::JavaRef<jstring>& jjson) {
  LemurXDispatchTabEvent(
      base::android::ConvertJavaStringToUTF8(env, jname),
      base::android::ConvertJavaStringToUTF8(env, jjson));
}

static void JNI_LemurXBridge_DispatchLuakitWidget(
    JNIEnv* env,
    jint id,
    const jni_zero::JavaRef<jstring>& jjson) {
  LemurXLuakitDispatch(
      "widget", id,
      jjson.is_null() ? "{}" : base::android::ConvertJavaStringToUTF8(env, jjson),
      0);
}

namespace {

void ReplyLuakitWidgetSync(int token, const std::string& verdict) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXBridge_onLuakitWidgetSyncResult(
      env, token, base::android::ConvertUTF8ToJavaString(env, verdict));
}

}  // namespace

static void JNI_LemurXBridge_DispatchLuakitWidgetSync(
    JNIEnv* env,
    jint id,
    const jni_zero::JavaRef<jstring>& jjson,
    jint token) {
  LemurXLuakitDispatchWithReply(
      "widget", id,
      jjson.is_null() ? "{}" : base::android::ConvertJavaStringToUTF8(env, jjson),
      base::BindOnce(&ReplyLuakitWidgetSync, token));
}

static void JNI_LemurXBridge_DispatchHttpResult(
    JNIEnv* env,
    jint request_id,
    const jni_zero::JavaRef<jstring>& jjson) {
  LemurXDispatchHttpResult(
      request_id,
      jjson.is_null() ? "{\"ok\":false,\"error\":\"no result\"}"
                      : base::android::ConvertJavaStringToUTF8(env, jjson));
}

// jni_zero (Chromium 154): 生成 Java->native 入口，必须在 JNI_LemurXBridge_* 定义之后调用。
DEFINE_JNI(LemurXBridge)
