// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/lemurx/lemurx_cookies.h"

#include "base/functional/bind.h"
#include "base/json/json_writer.h"
#include "base/memory/scoped_refptr.h"
#include "base/time/time.h"
#include "base/values.h"
#include "chrome/browser/lemurx/lemurx_sync_call.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/profiles/profile_manager.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/storage_partition.h"
#include "net/cookies/canonical_cookie.h"
#include "net/cookies/cookie_access_result.h"
#include "net/cookies/cookie_constants.h"
#include "net/cookies/cookie_inclusion_status.h"
#include "net/cookies/cookie_options.h"
#include "net/cookies/cookie_partition_key.h"
#include "net/cookies/cookie_partition_key_collection.h"
#include "services/network/public/mojom/cookie_manager.mojom.h"
#include "url/gurl.h"

namespace {

// Lua 线程阻塞等 cookie 回包的上限。以前是无限 Wait()：CookieManager 管道
// 一断，回包永远不来，Lua 线程就永久卡死。
constexpr base::TimeDelta kCookieTimeout = base::Seconds(8);

network::mojom::CookieManager* CookieManagerForLastProfile() {
  Profile* profile = ProfileManager::GetLastUsedProfileIfLoaded();
  if (!profile) {
    profile = ProfileManager::GetLastUsedProfile();
  }
  if (!profile) {
    return nullptr;
  }
  return profile->GetDefaultStoragePartition()
      ->GetCookieManagerForBrowserProcess();
}

}  // namespace

std::string LemurXGetCookiesJson(const std::string& url_spec) {
  GURL url(url_spec);
  if (!url.is_valid()) {
    return "[]";
  }

  // 结果放在堆上的 gate 里：CookieManager 的回包是异步的，而且管道断开时
  // 回包永远不来——等待必须带超时，超时后回包再写也只写 gate 不写栈。
  auto gate = base::MakeRefCounted<LemurXSyncGate>();
  gate->result = "[]";
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](GURL url, scoped_refptr<LemurXSyncGate> gate) {
            network::mojom::CookieManager* manager =
                CookieManagerForLastProfile();
            if (!manager) {
              gate->Signal();
              return;
            }
            manager->GetCookieList(
                url, net::CookieOptions::MakeAllInclusive(),
                net::CookiePartitionKeyCollection::ContainsAll(),
                base::BindOnce(
                    [](scoped_refptr<LemurXSyncGate> gate,
                       const net::CookieAccessResultList& included,
                       const net::CookieAccessResultList&) {
                      base::ListValue list;
                      for (const auto& item : included) {
                        base::DictValue dict;
                        dict.Set("name", item.cookie.Name());
                        dict.Set("value", item.cookie.Value());
                        dict.Set("domain", item.cookie.Domain());
                        dict.Set("path", item.cookie.Path());
                        dict.Set("httpOnly", item.cookie.IsHttpOnly());
                        dict.Set("secure", item.cookie.SecureAttribute());
                        list.Append(std::move(dict));
                      }
                      base::JSONWriter::Write(list, &gate->result);
                      gate->Signal();
                    },
                    gate));
          },
          url, gate));
  if (!gate->Wait(kCookieTimeout)) {
    return "[]";
  }
  return gate->result.empty() ? "[]" : gate->result;
}

bool LemurXSetCookie(const std::string& url_spec,
                       const std::string& cookie_line) {
  GURL url(url_spec);
  if (!url.is_valid() || cookie_line.empty()) {
    return false;
  }

  auto gate = base::MakeRefCounted<LemurXSyncGate>();
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](GURL url, std::string cookie_line,
             scoped_refptr<LemurXSyncGate> gate) {
            network::mojom::CookieManager* manager =
                CookieManagerForLastProfile();
            if (!manager) {
              gate->Signal();
              return;
            }
            net::CookieInclusionStatus status;
            std::unique_ptr<net::CanonicalCookie> cookie =
                net::CanonicalCookie::Create(
                    url, cookie_line, base::Time::Now(), std::nullopt,
                    std::nullopt, net::CookieSourceType::kOther, &status);
            if (!cookie) {
              gate->Signal();
              return;
            }
            manager->SetCanonicalCookie(
                *cookie, url, net::CookieOptions::MakeAllInclusive(),
                base::BindOnce(
                    [](scoped_refptr<LemurXSyncGate> gate,
                       net::CookieAccessResult result) {
                      gate->ok = result.status.IsInclude();
                      gate->Signal();
                    },
                    gate));
          },
          url, cookie_line, gate));
  if (!gate->Wait(kCookieTimeout)) {
    return false;
  }
  return gate->ok;
}
