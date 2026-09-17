// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_NATIVE_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_NATIVE_H_

#include <string>

#include "base/functional/callback_forward.h"

struct lua_State;

// luakit 兼容内核的原生原语层。
//
// 注册一个隐藏全局表 `__luakit`，里面是 Lua 内核（chrome/lemurx/luakit/kernel/）
// 需要而 lemurx.* 又没有的底层能力：RE2 正则、多实例 sqlite3、GURL 解析、
// URI 转义、进程 spawn、LuaFileSystem、单调时钟、idle 投递。
// 对外的 luakit / soup / sqlite3 / regex / timer / xdg / lfs 等全局名字
// 都由 Lua 内核在这层之上拼出来，这里不直接暴露任何 luakit 语义。
//
// 只在主（本地特权）状态注册；UGC 状态拿不到 __luakit。
void RegisterLemurXLuakitNative(lua_State* L);

// C++ → Lua 回调统一入口：在 Lua 线程上调用全局 `__luakit_dispatch(kind, id, ...)`。
// 用于 spawn 退出、idle 投递等异步回投。
void LemurXLuakitDispatch(const std::string& kind,
                         int id,
                         const std::string& a,
                         int b);

// 带答复版：在 Lua 线程上调用全局 `__luakit_dispatch_sync(kind, id, a)`，把它返回的
// 字符串交给 reply（在 Lua 线程上调用）。用于 UI 线程必须知道结果的事件
// （控件 key-press 是否被 Lua 吃掉）：Java 侧一边等答复一边替 Lua 代跑控件操作，
// 避免 UI↔Lua 互相等待。
void LemurXLuakitDispatchWithReply(
    const std::string& kind,
    int id,
    const std::string& a,
    base::OnceCallback<void(const std::string&)> reply);

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_LUAKIT_NATIVE_H_
