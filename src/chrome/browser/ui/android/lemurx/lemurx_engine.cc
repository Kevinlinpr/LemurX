// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_engine.h"

#include <algorithm>
#include <utility>

#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/logging.h"
#include "base/no_destructor.h"
#include "base/synchronization/waitable_event.h"
#include "base/task/task_traits.h"
#include "base/task/thread_pool.h"
#include "base/time/time.h"
#include "chrome/browser/ui/android/lemurx/lemurx_api.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"
#include "third_party/lua/src/lualib.h"

namespace {

// UGC 状态里的 load：只收文本 chunk。Lua VM 不校验字节码，
// 一段畸形字节码就能在浏览器进程里读写任意内存，所以 mode 强制 "t"。
int UgcSafeLoad(lua_State* L) {
  int n = lua_gettop(L);
  // 参数：chunk [, chunkname [, mode [, env]]]
  if (n < 3) {
    lua_settop(L, 3);
  }
  lua_pushstring(L, "t");
  lua_replace(L, 3);
  int nargs = n >= 4 ? 4 : 3;
  lua_settop(L, nargs);
  lua_pushvalue(L, lua_upvalueindex(1));
  lua_insert(L, 1);
  lua_call(L, nargs, LUA_MULTRET);
  return lua_gettop(L);
}

}  // namespace

LemurXEngine::ScopedPrivilege::ScopedPrivilege(LemurXEngine* engine,
                                                 bool privileged)
    : engine_(engine), previous_(engine->privileged_) {
  engine_->privileged_ = privileged;
}

LemurXEngine::ScopedPrivilege::~ScopedPrivilege() {
  engine_->privileged_ = previous_;
}

LemurXEngine::ScopedState::ScopedState(LemurXEngine* engine,
                                         lua_State* L,
                                         bool privileged)
    : engine_(engine),
      previous_state_(engine->current_),
      previous_privileged_(engine->privileged_) {
  engine_->current_ = L;
  // UGC 状态永远没有特权，不管调用方怎么说
  engine_->privileged_ = privileged && engine_->IsMainState(L);
}

LemurXEngine::ScopedState::~ScopedState() {
  engine_->current_ = previous_state_;
  engine_->privileged_ = previous_privileged_;
}

LemurXEngine* LemurXEngine::Get() {
  static base::NoDestructor<LemurXEngine> instance;
  return instance.get();
}

LemurXEngine::LemurXEngine() = default;

LemurXEngine::~LemurXEngine() {
  if (!lua_task_runner_ || !L_) {
    return;
  }
  if (lua_task_runner_->RunsTasksInCurrentSequence()) {
    CloseAllStates();
    return;
  }
  base::WaitableEvent event;
  lua_task_runner_->PostTask(
      FROM_HERE, base::BindOnce(
                     [](LemurXEngine* self, base::WaitableEvent* event) {
                       self->CloseAllStates();
                       event->Signal();
                     },
                     base::Unretained(this), &event));
  event.Wait();
}

void LemurXEngine::CloseAllStates() {
  for (auto& item : ugc_states_) {
    lua_close(item.second);
  }
  ugc_states_.clear();
  if (L_) {
    lua_close(L_);
    L_ = nullptr;
  }
}

void LemurXEngine::Start() {
  if (!lua_task_runner_) {
    lua_task_runner_ = base::ThreadPool::CreateSequencedTaskRunner(
        {base::MayBlock(), base::TaskPriority::USER_VISIBLE,
         base::TaskShutdownBehavior::SKIP_ON_SHUTDOWN});
  }
  enabled_.store(true, std::memory_order_release);
  lua_task_runner_->PostTask(
      FROM_HERE, base::BindOnce(&LemurXEngine::StartOnLuaThread,
                                base::Unretained(this)));
}

void LemurXEngine::Stop(base::OnceClosure on_stopped) {
  // 先关闸再清理：闸一关，后续 Eval/RunOnLuaThread 全部丢弃，
  // 已排队的任务在 Lua 线程上顺序跑完后才会执行 StopOnLuaThread。
  enabled_.store(false, std::memory_order_release);
  if (!lua_task_runner_) {
    if (on_stopped) {
      std::move(on_stopped).Run();
    }
    return;
  }
  lua_task_runner_->PostTask(
      FROM_HERE,
      base::BindOnce(&LemurXEngine::StopOnLuaThread, base::Unretained(this),
                     base::SequencedTaskRunner::HasCurrentDefault()
                         ? base::SequencedTaskRunner::GetCurrentDefault()
                         : nullptr,
                     std::move(on_stopped)));
}

void LemurXEngine::Eval(const std::string& chunk,
                          const std::string& name,
                          bool privileged) {
  // 未 Start()（用户关掉了 Lua）就丢弃：绝不能因为一次 eval 把引擎偷偷拉起来。
  if (!enabled() || !lua_task_runner_) {
    return;
  }
  lua_task_runner_->PostTask(
      FROM_HERE, base::BindOnce(&LemurXEngine::EvalOnLuaThread,
                                base::Unretained(this), chunk, name,
                                privileged));
}

void LemurXEngine::RunOnLuaThread(base::OnceClosure task) {
  if (!enabled() || !lua_task_runner_) {
    return;
  }
  lua_task_runner_->PostTask(FROM_HERE, std::move(task));
}

void LemurXEngine::PostDelayedOnLuaThread(base::OnceClosure task,
                                            base::TimeDelta delay) {
  if (!enabled() || !lua_task_runner_) {
    return;
  }
  lua_task_runner_->PostDelayedTask(FROM_HERE, std::move(task), delay);
}

std::string LemurXEngine::NameOf(lua_State* L) const {
  for (const auto& item : ugc_states_) {
    if (item.second == L) {
      return item.first;
    }
  }
  return "";
}

void LemurXEngine::StartOnLuaThread() {
  if (L_) {
    return;
  }
  L_ = luaL_newstate();
  if (!L_) {
    LOG(ERROR) << "LemurX: luaL_newstate failed";
    return;
  }
  OpenLocalLibs(L_);
  RegisterLemurXApi(L_);
  LOG(INFO) << "LemurX: runtime started";
}

// 本地脚本：最大自由度。io/os/debug/package 全开，load 收字节码，
// dofile/loadfile/require 都在。这是用户自己的设备、自己的脚本。
void LemurXEngine::OpenLocalLibs(lua_State* L) {
  luaL_openlibs(L);
}

// UGC 脚本：只开纯计算库；base 里去掉 dofile/loadfile，load 只收文本。
void LemurXEngine::OpenUgcLibs(lua_State* L) {
  luaL_requiref(L, LUA_GNAME, luaopen_base, 1);
  lua_pop(L, 1);
  luaL_requiref(L, LUA_TABLIBNAME, luaopen_table, 1);
  lua_pop(L, 1);
  luaL_requiref(L, LUA_STRLIBNAME, luaopen_string, 1);
  lua_pop(L, 1);
  luaL_requiref(L, LUA_MATHLIBNAME, luaopen_math, 1);
  lua_pop(L, 1);
  luaL_requiref(L, LUA_UTF8LIBNAME, luaopen_utf8, 1);
  lua_pop(L, 1);
  luaL_requiref(L, LUA_COLIBNAME, luaopen_coroutine, 1);
  lua_pop(L, 1);

  lua_getglobal(L, "load");
  lua_pushcclosure(L, UgcSafeLoad, 1);
  lua_setglobal(L, "load");
  lua_pushnil(L);
  lua_setglobal(L, "dofile");
  lua_pushnil(L);
  lua_setglobal(L, "loadfile");
}

lua_State* LemurXEngine::UgcStateFor(const std::string& name) {
  auto it = ugc_states_.find(name);
  if (it != ugc_states_.end()) {
    return it->second;
  }
  lua_State* L = luaL_newstate();
  if (!L) {
    LOG(ERROR) << "LemurX: ugc luaL_newstate failed " << name;
    return nullptr;
  }
  ugc_states_[name] = L;
  OpenUgcLibs(L);
  RegisterLemurXApi(L);
  if (!init_chunk_.empty()) {
    RunChunk(L, init_chunk_, "lua/init.lua", false);
  }
  LOG(INFO) << "LemurX: ugc state created " << name;
  return L;
}

void LemurXEngine::RunChunk(lua_State* L,
                              const std::string& chunk,
                              const std::string& name,
                              bool privileged) {
  ScopedState scope(this, L, privileged);
  const std::string chunk_name = name.empty() ? "=lemurx" : "=" + name;
  int status =
      luaL_loadbuffer(L, chunk.data(), chunk.size(), chunk_name.c_str());
  if (status != LUA_OK) {
    std::string err = lua_tostring(L, -1) ? lua_tostring(L, -1) : "load error";
    lua_pop(L, 1);
    LOG(ERROR) << "LemurX load error: " << err;
    return;
  }
  status = lua_pcall(L, 0, 0, 0);
  if (status != LUA_OK) {
    std::string err =
        lua_tostring(L, -1) ? lua_tostring(L, -1) : "runtime error";
    lua_pop(L, 1);
    LOG(ERROR) << "LemurX runtime error: " << err;
  }
}

void LemurXEngine::StopOnLuaThread(
    scoped_refptr<base::SequencedTaskRunner> reply_runner,
    base::OnceClosure on_stopped) {
  // 回调表里存着各状态的 registry ref，必须先于 lua_close 清掉
  // （清表不 unref：状态马上整个关掉）。
  LemurXResetLuaGlobals();
  CloseAllStates();
  current_ = nullptr;
  privileged_ = true;
  init_chunk_.clear();
  LOG(INFO) << "LemurX: runtime stopped";
  if (!on_stopped) {
    return;
  }
  if (reply_runner) {
    reply_runner->PostTask(FROM_HERE, std::move(on_stopped));
  } else {
    std::move(on_stopped).Run();
  }
}

void LemurXEngine::EvalOnLuaThread(const std::string& chunk,
                                     const std::string& name,
                                     bool privileged) {
  // Stop() 之后残留在队列里的 eval：不能借机把状态重新建起来
  if (!enabled()) {
    return;
  }
  StartOnLuaThread();
  if (!L_) {
    LOG(ERROR) << "LemurX: lua state is null";
    return;
  }
  if (name == "lua/init.lua") {
    // 记下来，之后每个 UGC 状态都要先跑一遍
    init_chunk_ = chunk;
  }
  if (privileged) {
    RunChunk(L_, chunk, name, true);
    return;
  }
  lua_State* L = UgcStateFor(name);
  if (!L) {
    return;
  }
  RunChunk(L, chunk, name, false);
}
