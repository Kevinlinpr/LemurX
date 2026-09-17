// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/lemurx/lemurx_url_pattern.h"

#include <string_view>

#include "base/strings/pattern.h"
#include "base/strings/string_util.h"
#include "url/gurl.h"

namespace lemurx {

namespace {

bool IsWebScheme(std::string_view scheme) {
  return scheme == "http" || scheme == "https" || scheme == "ws" ||
         scheme == "wss";
}

}  // namespace

UrlPattern::UrlPattern() = default;
UrlPattern::~UrlPattern() = default;
UrlPattern::UrlPattern(const UrlPattern&) = default;
UrlPattern& UrlPattern::operator=(const UrlPattern&) = default;

bool UrlPattern::Parse(const std::string& pattern) {
  valid_ = false;
  all_urls_ = false;
  scheme_.clear();
  host_.clear();
  match_subdomains_ = false;
  path_.clear();

  if (pattern == "<all_urls>") {
    all_urls_ = true;
    valid_ = true;
    return true;
  }

  size_t sep = pattern.find("://");
  if (sep == std::string::npos || sep == 0) {
    return false;
  }
  std::string scheme = base::ToLowerASCII(pattern.substr(0, sep));
  if (scheme != "*" && scheme != "file" && !IsWebScheme(scheme)) {
    return false;
  }

  std::string rest = pattern.substr(sep + 3);
  size_t slash = rest.find('/');
  std::string host = slash == std::string::npos ? rest : rest.substr(0, slash);
  std::string path = slash == std::string::npos ? "/" : rest.substr(slash);

  if (scheme == "file") {
    if (!host.empty()) {
      return false;
    }
  } else {
    if (host.empty()) {
      return false;
    }
    host = base::ToLowerASCII(host);
    if (host == "*") {
      host.clear();
      match_subdomains_ = true;
    } else if (base::StartsWith(host, "*.")) {
      host = host.substr(2);
      match_subdomains_ = true;
      if (host.empty() || host.find('*') != std::string::npos) {
        return false;
      }
    } else if (host.find('*') != std::string::npos) {
      // "*" is only allowed as the whole host or as a leading "*." label.
      return false;
    }
  }

  scheme_ = scheme;
  host_ = host;
  path_ = path;
  valid_ = true;
  return true;
}

bool UrlPattern::MatchesScheme(const std::string& scheme) const {
  if (scheme_ == "*") {
    return IsWebScheme(scheme);
  }
  return scheme_ == scheme;
}

bool UrlPattern::MatchesHost(const std::string& host) const {
  if (host_.empty()) {
    return true;  // "*"
  }
  if (host == host_) {
    return true;
  }
  if (!match_subdomains_) {
    return false;
  }
  return host.size() > host_.size() &&
         base::EndsWith(host, "." + host_, base::CompareCase::SENSITIVE);
}

bool UrlPattern::MatchesPath(const std::string& path_and_query) const {
  // base::MatchPattern treats '*' and '?' as wildcards; '?' inside URL
  // patterns is a literal query separator, so escape it.
  std::string glob;
  glob.reserve(path_.size() + 4);
  for (char c : path_) {
    if (c == '?') {
      glob += "\\?";
    } else {
      glob += c;
    }
  }
  return base::MatchPattern(path_and_query, glob);
}

bool UrlPattern::MatchesURL(const GURL& url) const {
  if (!valid_ || !url.is_valid()) {
    return false;
  }
  if (all_urls_) {
    return IsWebScheme(url.scheme()) || url.SchemeIsFile() ||
           url.SchemeIs("ftp") || url.SchemeIs("data");
  }
  // GURL accessors return std::string_view since Chromium ~150.
  if (!MatchesScheme(std::string(url.scheme()))) {
    return false;
  }
  if (scheme_ != "file" &&
      !MatchesHost(base::ToLowerASCII(std::string(url.host())))) {
    return false;
  }
  std::string path(url.path());
  if (url.has_query()) {
    path += "?";
    path += url.query();
  }
  return MatchesPath(path);
}

}  // namespace lemurx
