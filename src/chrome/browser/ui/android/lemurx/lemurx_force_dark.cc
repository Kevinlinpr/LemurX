// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_force_dark.h"

#include "base/no_destructor.h"
#include "base/strings/string_util.h"
#include "base/synchronization/lock.h"
#include "content/public/browser/web_contents.h"
#include "url/gurl.h"

namespace {

struct Holder {
  base::Lock lock;
  LemurXForceDarkState state;
};

Holder& GetHolder() {
  static base::NoDestructor<Holder> holder;
  return *holder;
}

bool HostMatches(const std::string& host, const std::string& pattern) {
  if (pattern.empty() || host.empty()) {
    return false;
  }
  std::string p = base::ToLowerASCII(pattern);
  if (base::StartsWith(p, "*.")) {
    p = p.substr(2);
  } else if (p[0] == '.') {
    p = p.substr(1);
  }
  if (host == p) {
    return true;
  }
  return host.size() > p.size() &&
         base::EndsWith(host, "." + p, base::CompareCase::SENSITIVE);
}

}  // namespace

void LemurXSetForceDark(LemurXForceDarkState state) {
  Holder& h = GetHolder();
  base::AutoLock lock(h.lock);
  h.state = std::move(state);
}

LemurXForceDarkState LemurXGetForceDark() {
  Holder& h = GetHolder();
  base::AutoLock lock(h.lock);
  return h.state;
}

std::optional<bool> LemurXForceDarkOverride(
    content::WebContents* web_contents) {
  Holder& h = GetHolder();
  base::AutoLock lock(h.lock);
  if (!h.state.enabled.has_value()) {
    return std::nullopt;
  }
  bool enabled = *h.state.enabled;
  if (!web_contents || h.state.exceptions.empty()) {
    return enabled;
  }
  GURL url = web_contents->GetVisibleURL();
  if (!url.is_valid()) {
    url = web_contents->GetLastCommittedURL();
  }
  const std::string host = base::ToLowerASCII(url.host());
  for (const std::string& pattern : h.state.exceptions) {
    if (HostMatches(host, pattern)) {
      return !enabled;
    }
  }
  return enabled;
}
