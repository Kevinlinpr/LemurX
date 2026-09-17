// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_ENGINE_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_ENGINE_H_

#include <atomic>
#include <map>
#include <string>

#include "base/functional/callback.h"
#include "base/memory/scoped_refptr.h"
#include "base/task/sequenced_task_runner.h"
#include "base/time/time.h"

struct lua_State;

// LemurX Lua 运行时。
//
// 两层信任域：
//  * 主状态 L_：filesDir/lua/*.lua 本地脚本，全部标准库全开（io/os/debug/package），
//    最大自由度，用户自己的设备自己做主。
//  * UGC 状态：filesDir/lua/ugc/*.lua 每个脚本一个独立 lua_State，只开安全库，
//    load 只收文本、没有 dofile/loadfile，永远 privileged=false。
//    互不共享全局表，所以 UGC 改不了本地脚本的 lemurx.*/pairs/string 元表，
//    也就拿不到本地脚本回调里的特权位。
//
// 所有状态都跑在同一条 sequenced 线程上，Lua 侧永远单线程。
class LemurXEngine {
 public:
  class ScopedPrivilege {
   public:
    ScopedPrivilege(LemurXEngine* engine, bool privileged);
    ~ScopedPrivilege();

   private:
    LemurXEngine* engine_;
    bool previous_;
  };

  // 进入某个状态执行回调：同时切 current_ 和特权位。
  class ScopedState {
   public:
    ScopedState(LemurXEngine* engine, lua_State* L, bool privileged);
    ~ScopedState();

   private:
    LemurXEngine* engine_;
    lua_State* previous_state_;
    bool previous_privileged_;
  };

  static LemurXEngine* Get();

  LemurXEngine();
  ~LemurXEngine();

  LemurXEngine(const LemurXEngine&) = delete;
  LemurXEngine& operator=(const LemurXEngine&) = delete;

  void Start();
  // 用户在「Lua 脚本」里关掉开关时调用（任意线程）：之后 Eval / RunOnLuaThread /
  // PostDelayedOnLuaThread 全部静默丢弃，Lua 线程上先清掉 lemurx_api 的全局回调表
  // （事件、定时器、UI 点击、http 回包……），再 lua_close 主状态和全部 UGC 状态。
  // 已经排队的任务照常跑到，但它们拿到的 state() 是 nullptr / 回调表为空，各自
  // 早退。on_stopped 在 Lua 线程清理完成后回到调用方线程。再调 Start() 会重新
  // 建一个干净的状态——这就是「重载脚本」。
  void Stop(base::OnceClosure on_stopped);
  // Start() 后为 true，Stop() 后为 false；Java 侧不再 eval，原生侧不再派发。
  bool enabled() const { return enabled_.load(std::memory_order_acquire); }
  void Eval(const std::string& chunk,
            const std::string& name,
            bool privileged = true);
  void RunOnLuaThread(base::OnceClosure task);
  void PostDelayedOnLuaThread(base::OnceClosure task, base::TimeDelta delay);

  // 主（本地特权）状态。
  lua_State* state() { return L_; }
  // 当前正在执行的状态（主或某个 UGC）；只在 Lua 线程有意义。
  lua_State* current() { return current_ ? current_ : L_; }
  bool privileged() const { return privileged_; }
  // 某个 lua_State 是否是主状态。
  bool IsMainState(lua_State* L) const { return L != nullptr && L == L_; }
  // 找到 UGC 状态对应的脚本名；主状态返回 ""。
  std::string NameOf(lua_State* L) const;

 private:
  void StartOnLuaThread();
  void StopOnLuaThread(scoped_refptr<base::SequencedTaskRunner> reply_runner,
                       base::OnceClosure on_stopped);
  void EvalOnLuaThread(const std::string& chunk,
                       const std::string& name,
                       bool privileged);
  lua_State* UgcStateFor(const std::string& name);
  void CloseAllStates();
  void OpenLocalLibs(lua_State* L);
  void OpenUgcLibs(lua_State* L);
  void RunChunk(lua_State* L,
                const std::string& chunk,
                const std::string& name,
                bool privileged);

  lua_State* L_ = nullptr;
  lua_State* current_ = nullptr;
  std::map<std::string, lua_State*> ugc_states_;
  // apk 里的 init.lua，每个新 UGC 状态都要先跑一遍
  std::string init_chunk_;
  scoped_refptr<base::SequencedTaskRunner> lua_task_runner_;
  bool privileged_ = true;
  // Start() 置 true，Stop() 置 false。UI 线程写、Lua 线程和 UI 线程读。
  std::atomic<bool> enabled_{false};
};

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_ENGINE_H_
