// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/lemurx/lemurx_cookies.h"

#include "base/functional/bind.h"
#include "base/json/json_writer.h"
#include "base/synchronization/waitable_event.h"
#include "base/time/time.h"
#include "base/values.h"
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

  std::string json = "[]";
  base::WaitableEvent event;
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(
                     [](GURL url, std::string* json, base::WaitableEvent* event) {
                       network::mojom::CookieManager* manager =
                           CookieManagerForLastProfile();
                       if (!manager) {
                         event->Signal();
                         return;
                       }
                       manager->GetCookieList(
                           url, net::CookieOptions::MakeAllInclusive(),
                           net::CookiePartitionKeyCollection::Todo(),
                           base::BindOnce(
                               [](std::string* json, base::WaitableEvent* event,
                                  const net::CookieAccessResultList& included,
                                  const net::CookieAccessResultList&) {
                                 base::Value::List list;
                                 for (const auto& item : included) {
                                   base::Value::Dict dict;
                                   dict.Set("name", item.cookie.Name());
                                   dict.Set("value", item.cookie.Value());
                                   dict.Set("domain", item.cookie.Domain());
                                   dict.Set("path", item.cookie.Path());
                                   dict.Set("httpOnly",
                                            item.cookie.IsHttpOnly());
                                   dict.Set("secure",
                                            item.cookie.SecureAttribute());
                                   list.Append(std::move(dict));
                                 }
                                 base::JSONWriter::Write(list, json);
                                 event->Signal();
                               },
                               json, event));
                     },
                     url, &json, &event));
  event.Wait();
  return json.empty() ? "[]" : json;
}

bool LemurXSetCookie(const std::string& url_spec,
                       const std::string& cookie_line) {
  GURL url(url_spec);
  if (!url.is_valid() || cookie_line.empty()) {
    return false;
  }

  bool ok = false;
  base::WaitableEvent event;
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(
                     [](GURL url, std::string cookie_line, bool* ok,
                        base::WaitableEvent* event) {
                       network::mojom::CookieManager* manager =
                           CookieManagerForLastProfile();
                       if (!manager) {
                         event->Signal();
                         return;
                       }
                       net::CookieInclusionStatus status;
                       std::unique_ptr<net::CanonicalCookie> cookie =
                           net::CanonicalCookie::Create(
                               url, cookie_line, base::Time::Now(),
                               std::nullopt, std::nullopt,
                               net::CookieSourceType::kOther, &status);
                       if (!cookie) {
                         event->Signal();
                         return;
                       }
                       manager->SetCanonicalCookie(
                           *cookie, url, net::CookieOptions::MakeAllInclusive(),
                           base::BindOnce(
                               [](bool* ok, base::WaitableEvent* event,
                                  net::CookieAccessResult result) {
                                 *ok = result.status.IsInclude();
                                 event->Signal();
                               },
                               ok, event));
                     },
                     url, cookie_line, &ok, &event));
  event.Wait();
  return ok;
}
