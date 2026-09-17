// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/lemurx/lemurx_sync_call.h"

#include <utility>

#include "base/functional/bind.h"
#include "base/logging.h"
#include "content/public/browser/browser_thread.h"

LemurXSyncGate::LemurXSyncGate()
    : event_(base::WaitableEvent::ResetPolicy::MANUAL,
             base::WaitableEvent::InitialState::NOT_SIGNALED) {}

LemurXSyncGate::~LemurXSyncGate() = default;

void LemurXSyncGate::RunUnlessAbandoned(base::OnceClosure closure) {
  base::AutoLock lock(lock_);
  if (abandoned_) {
    return;
  }
  std::move(closure).Run();
}

void LemurXSyncGate::RunAndSignal(base::OnceClosure closure) {
  RunUnlessAbandoned(std::move(closure));
  event_.Signal();
}

bool LemurXSyncGate::Wait(base::TimeDelta timeout) {
  if (event_.TimedWait(timeout)) {
    return true;
  }
  // 拿锁：若 UI 线程正在闭包里，等它跑完；之后任何闭包都不再执行。
  base::AutoLock lock(lock_);
  if (event_.IsSignaled()) {
    return true;
  }
  abandoned_ = true;
  return false;
}

bool LemurXSyncGate::abandoned() const {
  base::AutoLock lock(lock_);
  return abandoned_;
}

bool LemurXRunOnUiSync(base::OnceClosure closure,
                       base::TimeDelta timeout,
                       const char* what) {
  if (content::BrowserThread::CurrentlyOn(content::BrowserThread::UI)) {
    std::move(closure).Run();
    return true;
  }
  auto gate = base::MakeRefCounted<LemurXSyncGate>();
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(&LemurXSyncGate::RunAndSignal, gate,
                                std::move(closure)));
  if (!gate->Wait(timeout)) {
    LOG(WARNING) << "LemurX: UI op timed out: " << (what ? what : "?");
    return false;
  }
  return true;
}
