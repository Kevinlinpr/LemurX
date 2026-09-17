// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_LEMURX_LEMURX_NET_RULES_H_
#define CHROME_BROWSER_LEMURX_LEMURX_NET_RULES_H_

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "base/synchronization/lock.h"
#include "chrome/browser/lemurx/lemurx_url_pattern.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "url/gurl.h"

// Lua-owned network rules evaluated on the URLLoader hot path.
// Matching is synchronous C++ (no Lua callback) so ad-blocking stays cheap.
class LemurXNetRules {
 public:
  enum class Action {
    kBlock,
    kRedirect,
    kModify,
  };

  struct Rule {
    int id = 0;
    std::string match;
    Action action = Action::kBlock;
    GURL redirect_url;
    std::vector<std::string> types;
    std::map<std::string, std::string> request_headers;
    std::vector<std::string> remove_request_headers;
    std::map<std::string, std::string> response_headers;
    std::vector<std::string> remove_response_headers;
    std::vector<std::pair<std::string, std::string>> replace_body;
    std::string request_body;
    std::vector<std::pair<std::string, std::string>> replace_request_body;
    lemurx::UrlPattern pattern;
    bool pattern_valid = false;

    bool Matches(const GURL& url,
                 network::mojom::RequestDestination destination) const;
    bool NeedsBodyRewrite() const { return !replace_body.empty(); }
    bool NeedsRequestBodyRewrite() const {
      return !request_body.empty() || !replace_request_body.empty();
    }
  };

  static void ApplyBodyReplacements(
      std::string* body,
      const std::vector<std::pair<std::string, std::string>>& replacements);
  static bool IsRewritableMime(const std::string& mime_type);

  static LemurXNetRules* Get();

  int AddRule(Rule rule);
  bool RemoveRule(int id);
  void Clear();
  bool HasRules() const;
  std::vector<Rule> List() const;
  std::optional<Rule> FindMatch(
      const GURL& url,
      network::mojom::RequestDestination destination) const;

  static Action ParseAction(const std::string& action);
  static const char* ActionName(Action action);

  LemurXNetRules() = default;

 private:
  void CompilePattern(Rule* rule);

  mutable base::Lock lock_;
  std::vector<Rule> rules_;
  int next_id_ = 1;
};

#endif  // CHROME_BROWSER_LEMURX_LEMURX_NET_RULES_H_
