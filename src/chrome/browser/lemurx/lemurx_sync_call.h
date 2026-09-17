// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_LEMURX_LEMURX_SYNC_CALL_H_
#define CHROME_BROWSER_LEMURX_LEMURX_SYNC_CALL_H_

#include <string>

#include "base/functional/callback.h"
#include "base/memory/ref_counted.h"
#include "base/synchronization/lock.h"
#include "base/synchronization/waitable_event.h"
#include "base/time/time.h"

// Lua 跑在线程池 sequence 上，WebContents / CookieManager / DevTools 只能在
// UI 线程碰，所以 Lua 原语经常要“投到 UI 线程并阻塞等结果”。
//
// 这类等待必须带超时（UI 线程可能正被别的同步等待占住），而一旦超时，
// 等待方的栈帧就会销毁——此前的写法把 &ok / &json / &event 这类栈地址绑进
// 闭包，超时后 UI 线程再去写就是 use-after-free。这里把状态放到堆上的
// 引用计数对象里，并保证：
//   * 超时之后闭包不会再执行（Abandon 与 Run 互斥）；
//   * 若超时瞬间闭包正在执行，等待方会等它跑完再返回，栈仍然有效。
class LemurXSyncGate : public base::RefCountedThreadSafe<LemurXSyncGate> {
 public:
  LemurXSyncGate();

  // UI 线程：未被放弃则执行 closure。执行期间持锁，等待方超时也会等它结束。
  // 不会 Signal——closure 内部完成时（可能是异步回包）自己调 Signal()。
  void RunUnlessAbandoned(base::OnceClosure closure);

  // UI 线程：同 RunUnlessAbandoned，但 closure 返回后立即 Signal。
  void RunAndSignal(base::OnceClosure closure);

  // 等待方：完成返回 true；超时返回 false，并且之后不再执行任何闭包。
  bool Wait(base::TimeDelta timeout);

  void Signal() { event_.Signal(); }

  // 异步回包时是否还有人在等；回包若发现已放弃就别再写共享状态。
  bool abandoned() const;

  // 常用的结果槽，供闭包填写、等待方读取（Wait 返回 true 后读）。
  std::string result;
  bool ok = false;

 private:
  friend class base::RefCountedThreadSafe<LemurXSyncGate>;
  ~LemurXSyncGate();

  mutable base::Lock lock_;
  bool abandoned_ = false;
  base::WaitableEvent event_;
};

// 在 UI 线程同步执行 closure：当前已在 UI 线程则直接跑；否则投递并等待，
// 超时返回 false（closure 保证不会在返回后再执行）。`what` 只用于日志。
bool LemurXRunOnUiSync(base::OnceClosure closure,
                       base::TimeDelta timeout,
                       const char* what);

#endif  // CHROME_BROWSER_LEMURX_LEMURX_SYNC_CALL_H_
