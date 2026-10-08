// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// Agent 底座的 Lua 绑定。#include 进 lemurx_api.cc 的匿名命名空间（在 CallUiOp /
// RequirePrivilege / LuaToJson 之后、注册函数之前），依赖那里的辅助函数。
//
//   lemurx.ui.tap / longPress / swipe / screenshot   屏幕坐标输入、整窗截图
//   lemurx.tabs.viewport                              CSS <-> 屏幕坐标映射
//   lemurx.system.*                                   跨 App 自动化（无障碍服务，特权）
//
// 这些都是"能力"：不注册任何监听、不劫持任何手势，脚本不调用就等于不存在。

// 把 (x, y[, "dp"|{...}]) 或 ({x=, y=, ...}) 统一成 JSON，并写入特权位。
std::string AgentPointOptions(lua_State* L, const char* xkey, const char* ykey) {
  base::DictValue dict;
  if (lua_istable(L, 1)) {
    std::optional<base::Value> parsed =
        base::JSONReader::Read(LuaToJson(L, 1), base::JSON_PARSE_RFC);
    if (parsed && parsed->is_dict()) {
      dict = std::move(parsed->GetDict());
    }
  } else {
    dict.Set(xkey, luaL_checknumber(L, 1));
    dict.Set(ykey, luaL_checknumber(L, 2));
    if (lua_istable(L, 3)) {
      std::optional<base::Value> extra =
          base::JSONReader::Read(LuaToJson(L, 3), base::JSON_PARSE_RFC);
      if (extra && extra->is_dict()) {
        dict.Merge(std::move(extra->GetDict()));
      }
    } else if (lua_isstring(L, 3)) {
      dict.Set("unit", lua_tostring(L, 3));
    }
  }
  dict.Set("privileged", LemurXEngine::Get()->privileged());
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return json;
}

base::DictValue AgentTableOrEmpty(lua_State* L, int index) {
  base::DictValue dict;
  if (lua_istable(L, index)) {
    std::optional<base::Value> parsed =
        base::JSONReader::Read(LuaToJson(L, index), base::JSON_PARSE_RFC);
    if (parsed && parsed->is_dict()) {
      dict = std::move(parsed->GetDict());
    }
  }
  return dict;
}

// lemurx.ui.tap(x, y[, "dp"|{unit=,hold=}])  屏幕坐标点按（与 ui.dump 的 x/y 同一坐标系）
int LuaUiTap(lua_State* L) {
  return CallUiOp(L, "tap", AgentPointOptions(L, "x", "y"));
}

// lemurx.ui.longPress(x, y[, opts])
int LuaUiLongPress(lua_State* L) {
  return CallUiOp(L, "longpress", AgentPointOptions(L, "x", "y"));
}

// lemurx.ui.swipe(x1, y1, x2, y2[, durationMs[, "dp"]])  或 swipe({x1=,y1=,x2=,y2=,duration=})
int LuaUiSwipe(lua_State* L) {
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
    if (lua_isstring(L, 6)) {
      dict.Set("unit", lua_tostring(L, 6));
    }
    base::JSONWriter::Write(base::Value(std::move(dict)), &options);
  }
  return CallUiOp(L, "swipe", options);
}

// lemurx.ui.screenshot([{scale=0.5, quality=70, base64=true}])  整个浏览器窗口
int LuaUiScreenshot(lua_State* L) {
  base::DictValue dict = AgentTableOrEmpty(L, 1);
  dict.Set("privileged", LemurXEngine::Get()->privileged());
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "screenshot", json);
}

// lemurx.tabs.viewport([tabId])  网页 View 的屏幕位置 + contentOffsetY + pageScale
int LuaTabsViewport(lua_State* L) {
  base::DictValue dict = AgentTableOrEmpty(L, 1);
  if (!lua_istable(L, 1)) {
    dict.Set("tab", static_cast<int>(luaL_optinteger(L, 1, 0)));
  }
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  return CallUiOp(L, "viewport", json);
}

// lemurx.system.<method>(...)  —— 单一 JNI 入口，method 由闭包 upvalue 给出。
// 接受一张表（原样转 JSON），也接受几种便捷写法：
//   system.find("文本")            system.global("back")        system.launch("com.x" | "https://…")
//   system.click(ref)              system.scroll(ref, "up")     system.setText(ref | "查询文本", "值")
//   system.tap(x, y[, "dp"])       system.swipe(x1, y1, x2, y2[, ms])   system.events(true)
int LuaSystemCall(lua_State* L) {
  const char* method_c = lua_tostring(L, lua_upvalueindex(1));
  std::string method = method_c ? method_c : "";
  RequirePrivilege(L, "lemurx.system.*");
  base::DictValue dict;
  if (lua_istable(L, 1)) {
    dict = AgentTableOrEmpty(L, 1);
  } else if (lua_type(L, 1) == LUA_TSTRING) {
    const char* s = lua_tostring(L, 1);
    if (method == "global") {
      dict.Set("action", s);
    } else if (method == "launch") {
      dict.Set(std::string(s).find(':') != std::string::npos ? "url" : "package", s);
    } else {
      dict.Set("text", s);
    }
    if (lua_istable(L, 2)) {
      dict.Merge(AgentTableOrEmpty(L, 2));
    } else if (lua_isstring(L, 2) && method == "setText") {
      dict.Set("value", lua_tostring(L, 2));
    }
  } else if (lua_isnumber(L, 1)) {
    if (method == "swipe") {
      dict.Set("x1", luaL_checknumber(L, 1));
      dict.Set("y1", luaL_checknumber(L, 2));
      dict.Set("x2", luaL_checknumber(L, 3));
      dict.Set("y2", luaL_checknumber(L, 4));
      if (lua_isnumber(L, 5)) {
        dict.Set("duration", static_cast<int>(lua_tointeger(L, 5)));
      }
      if (lua_isstring(L, 6)) {
        dict.Set("unit", lua_tostring(L, 6));
      }
    } else if (method == "tap" || method == "longPress") {
      dict.Set("x", luaL_checknumber(L, 1));
      dict.Set("y", luaL_checknumber(L, 2));
      if (lua_isstring(L, 3)) {
        dict.Set("unit", lua_tostring(L, 3));
      }
    } else {
      // click(ref) / longClick(ref) / focus(ref) / scroll(ref, dir) / setText(ref, text)
      dict.Set("ref", static_cast<int>(lua_tointeger(L, 1)));
      if (lua_isstring(L, 2)) {
        dict.Set(method == "scroll" ? "dir" : "text", lua_tostring(L, 2));
      }
    }
  } else if (lua_isboolean(L, 1)) {
    dict.Set("enable", lua_toboolean(L, 1) != 0);
  }
  dict.Set("privileged", true);
  std::string json;
  base::JSONWriter::Write(base::Value(std::move(dict)), &json);
  JNIEnv* env = base::android::AttachCurrentThread();
  return PushJson(
      L, JavaString(env, Java_LemurXBridge_systemCall(
                             env, base::android::ConvertUTF8ToJavaString(env, method),
                             base::android::ConvertUTF8ToJavaString(env, json))));
}

void SetSystemFunction(lua_State* L, const char* name, const char* method) {
  lua_pushstring(L, method);
  lua_pushcclosure(L, LuaSystemCall, 1);
  lua_setfield(L, -2, name);
}

// 注册 lemurx.system 表（调用时栈顶是 lemurx 表）。
void RegisterLemurXSystemTable(lua_State* L) {
  lua_newtable(L);
  SetSystemFunction(L, "status", "status");
  SetSystemFunction(L, "openSettings", "openSettings");
  SetSystemFunction(L, "events", "events");
  SetSystemFunction(L, "foreground", "foreground");
  SetSystemFunction(L, "windows", "windows");
  SetSystemFunction(L, "tree", "tree");
  SetSystemFunction(L, "find", "find");
  SetSystemFunction(L, "click", "click");
  SetSystemFunction(L, "longClick", "longClick");
  SetSystemFunction(L, "setText", "setText");
  SetSystemFunction(L, "focus", "focus");
  SetSystemFunction(L, "scroll", "scroll");
  SetSystemFunction(L, "tap", "tap");
  SetSystemFunction(L, "longPress", "longPress");
  SetSystemFunction(L, "swipe", "swipe");
  SetSystemFunction(L, "global", "global");
  SetSystemFunction(L, "screenshot", "screenshot");
  SetSystemFunction(L, "launch", "launch");
  lua_setfield(L, -2, "system");
}
