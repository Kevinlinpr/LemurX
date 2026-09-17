// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_CDP_H_
#define CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_CDP_H_

#include <string>

namespace content {
class WebContents;
}

// 由 lemurx_api.cc 提供，必须在 UI 线程调用。
content::WebContents* LemurXWebContentsForTab(int tab_id);

// 由 lemurx_api.cc 提供：Java LemurXBridge.luakitEnv() 的 JSON（任意线程）。
std::string LemurXLuakitEnvJson();

// 由 lemurx_api.cc 提供：luakit 控件树宿主 LemurXWidgetHost.op（任意线程，
// 内部切 UI 线程同步执行）。返回 {"ok":bool,"value":...,"error":...} JSON。
std::string LemurXLuakitWidgetOp(const std::string& op,
                                   int id,
                                   const std::string& json);

std::string LemurXCdpSend(int tab_id,
                            const std::string& method,
                            const std::string& params_json,
                            int timeout_ms);
std::string LemurXCdpSendHost(const std::string& host_id,
                                const std::string& method,
                                const std::string& params_json,
                                int timeout_ms);
std::string LemurXCdpTargets();
bool LemurXCdpAttach(int tab_id);
void LemurXCdpDetach(int tab_id);
// UI 线程。断开全部 DevTools 会话（按 tab 和按 host id 附着的都算）。
void LemurXCdpDetachAll();
std::string LemurXCdpVersion();
bool LemurXCdpInspect(int tab_id, int x, int y);

#endif  // CHROME_BROWSER_UI_ANDROID_LEMURX_LEMURX_CDP_H_
