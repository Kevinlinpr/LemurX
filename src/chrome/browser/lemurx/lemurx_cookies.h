// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_LEMURX_LEMURX_COOKIES_H_
#define CHROME_BROWSER_LEMURX_LEMURX_COOKIES_H_

#include <string>

// Profile CookieManager 同步封装，必须在 Lua 线程（可阻塞）上调用。
std::string LemurXGetCookiesJson(const std::string& url);
bool LemurXSetCookie(const std::string& url, const std::string& cookie_line);

#endif  // CHROME_BROWSER_LEMURX_LEMURX_COOKIES_H_
