// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_LEMURX_LEMURX_URL_PATTERN_H_
#define CHROME_BROWSER_LEMURX_LEMURX_URL_PATTERN_H_

#include <string>

class GURL;

namespace lemurx {

// Match patterns for lemurx.net.addRule({match=...}). Same grammar as the
// well-known browser match patterns, implemented here so LemurX has no
// dependency on any extension system:
//
//   <all_urls>
//   <scheme>://<host><path>
//     scheme : "*" (= http | https | ws | wss) | http | https | ws | wss | file
//     host   : "*" | "*.example.com" (domain and any subdomain) | "example.com"
//     path   : "/" followed by anything; "*" matches any run of characters
//
// Matching ignores the URL fragment; the query is part of the path glob.
class UrlPattern {
 public:
  UrlPattern();
  ~UrlPattern();
  UrlPattern(const UrlPattern&);
  UrlPattern& operator=(const UrlPattern&);

  // Returns false (and leaves the pattern invalid) on a malformed string.
  bool Parse(const std::string& pattern);
  bool is_valid() const { return valid_; }

  bool MatchesURL(const GURL& url) const;

 private:
  bool MatchesScheme(const std::string& scheme) const;
  bool MatchesHost(const std::string& host) const;
  bool MatchesPath(const std::string& path_and_query) const;

  bool valid_ = false;
  bool all_urls_ = false;
  std::string scheme_;  // "*" or concrete scheme
  std::string host_;    // "" (any), "example.com"
  bool match_subdomains_ = false;
  std::string path_;    // glob, always starts with "/"
};

}  // namespace lemurx

#endif  // CHROME_BROWSER_LEMURX_LEMURX_URL_PATTERN_H_
