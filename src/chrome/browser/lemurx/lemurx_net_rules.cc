// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/lemurx/lemurx_net_rules.h"

#include <algorithm>

#include "base/containers/contains.h"
#include "base/no_destructor.h"
#include "base/strings/string_util.h"

namespace {

std::string DestinationName(network::mojom::RequestDestination destination) {
  switch (destination) {
    case network::mojom::RequestDestination::kDocument:
      return "document";
    case network::mojom::RequestDestination::kFrame:
    case network::mojom::RequestDestination::kIframe:
    case network::mojom::RequestDestination::kFencedframe:
      return "sub_frame";
    case network::mojom::RequestDestination::kScript:
      return "script";
    case network::mojom::RequestDestination::kImage:
      return "image";
    case network::mojom::RequestDestination::kStyle:
      return "stylesheet";
    case network::mojom::RequestDestination::kFont:
      return "font";
    case network::mojom::RequestDestination::kAudio:
    case network::mojom::RequestDestination::kVideo:
    case network::mojom::RequestDestination::kTrack:
      return "media";
    case network::mojom::RequestDestination::kWorker:
    case network::mojom::RequestDestination::kSharedWorker:
    case network::mojom::RequestDestination::kServiceWorker:
      return "worker";
    case network::mojom::RequestDestination::kManifest:
      return "manifest";
    case network::mojom::RequestDestination::kEmpty:
      return "xmlhttprequest";
    default:
      return "other";
  }
}

}  // namespace

LemurXNetRules* LemurXNetRules::Get() {
  static base::NoDestructor<LemurXNetRules> instance;
  return instance.get();
}

void LemurXNetRules::ApplyBodyReplacements(
    std::string* body,
    const std::vector<std::pair<std::string, std::string>>& replacements) {
  if (!body) {
    return;
  }
  for (const auto& item : replacements) {
    if (item.first.empty()) {
      continue;
    }
    base::ReplaceSubstringsAfterOffset(body, 0, item.first, item.second);
  }
}

bool LemurXNetRules::IsRewritableMime(const std::string& mime_type) {
  if (mime_type.empty()) {
    return false;
  }
  if (base::StartsWith(mime_type, "text/",
                       base::CompareCase::INSENSITIVE_ASCII)) {
    return true;
  }
  static constexpr const char* kJsonJsXml[] = {
      "application/javascript", "application/x-javascript",
      "application/ecmascript", "application/json",
      "application/xml",        "application/xhtml+xml",
      "application/ld+json"};
  for (const char* mime : kJsonJsXml) {
    if (base::StartsWith(mime_type, mime,
                         base::CompareCase::INSENSITIVE_ASCII)) {
      return true;
    }
  }
  return false;
}

LemurXNetRules::Action LemurXNetRules::ParseAction(
    const std::string& action) {
  if (action == "redirect") {
    return Action::kRedirect;
  }
  if (action == "modify") {
    return Action::kModify;
  }
  return Action::kBlock;
}

const char* LemurXNetRules::ActionName(Action action) {
  switch (action) {
    case Action::kRedirect:
      return "redirect";
    case Action::kModify:
      return "modify";
    case Action::kBlock:
    default:
      return "block";
  }
}

void LemurXNetRules::CompilePattern(Rule* rule) {
  rule->pattern = URLPattern(URLPattern::SCHEME_HTTP | URLPattern::SCHEME_HTTPS |
                             URLPattern::SCHEME_WS | URLPattern::SCHEME_WSS);
  std::string match = rule->match;
  if (match.empty() || match == "*" || match == "<all_urls>") {
    rule->pattern_valid =
        rule->pattern.Parse("<all_urls>") == URLPattern::ParseResult::kSuccess;
    return;
  }
  if (rule->pattern.Parse(match) == URLPattern::ParseResult::kSuccess) {
    rule->pattern_valid = true;
    return;
  }
  if (match.find("://") == std::string::npos) {
    std::string wrapped = "*://*." + match + "/*";
    if (rule->pattern.Parse(wrapped) == URLPattern::ParseResult::kSuccess) {
      rule->pattern_valid = true;
      return;
    }
    wrapped = "*://" + match + "/*";
    if (rule->pattern.Parse(wrapped) == URLPattern::ParseResult::kSuccess) {
      rule->pattern_valid = true;
      return;
    }
  }
  rule->pattern_valid = false;
}

bool LemurXNetRules::Rule::Matches(
    const GURL& url,
    network::mojom::RequestDestination destination) const {
  if (!types.empty()) {
    const std::string dest = DestinationName(destination);
    if (!base::Contains(types, dest) &&
        !(dest == "xmlhttprequest" && base::Contains(types, "fetch"))) {
      return false;
    }
  }
  if (pattern_valid) {
    return pattern.MatchesURL(url);
  }
  return url.spec().find(match) != std::string::npos;
}

int LemurXNetRules::AddRule(Rule rule) {
  CompilePattern(&rule);
  base::AutoLock lock(lock_);
  rule.id = next_id_++;
  int id = rule.id;
  rules_.push_back(std::move(rule));
  return id;
}

bool LemurXNetRules::RemoveRule(int id) {
  base::AutoLock lock(lock_);
  auto it = std::remove_if(rules_.begin(), rules_.end(),
                           [id](const Rule& rule) { return rule.id == id; });
  if (it == rules_.end()) {
    return false;
  }
  rules_.erase(it, rules_.end());
  return true;
}

void LemurXNetRules::Clear() {
  base::AutoLock lock(lock_);
  rules_.clear();
}

bool LemurXNetRules::HasRules() const {
  base::AutoLock lock(lock_);
  return !rules_.empty();
}

std::vector<LemurXNetRules::Rule> LemurXNetRules::List() const {
  base::AutoLock lock(lock_);
  return rules_;
}

std::optional<LemurXNetRules::Rule> LemurXNetRules::FindMatch(
    const GURL& url,
    network::mojom::RequestDestination destination) const {
  base::AutoLock lock(lock_);
  for (const auto& rule : rules_) {
    if (rule.Matches(url, destination)) {
      return rule;
    }
  }
  return std::nullopt;
}
