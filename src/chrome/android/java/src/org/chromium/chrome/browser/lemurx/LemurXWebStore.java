// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import java.util.HashSet;
import java.util.Set;

import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabUtils;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.navigation_controller.UserAgentOverrideOption;
import org.chromium.url.GURL;

/**
 * 官方 Chrome 网上应用店在手机上仍按桌面页打开。这里只把页面收到设备宽度里：
 * 去掉桌面最小宽度，窄屏时卡片单列。安装仍由商店页面自己的接口完成。
 */
public final class LemurXWebStore {
    private static final String STORE_ROOT = "https://chromewebstore.google.com/";
    private static final Set<Integer> sLeftUnsupported = new HashSet<>();

    private static final String LAYOUT =
            "(function(){if(window.__aeroChromeStore)return;window.__aeroChromeStore=true;"
                    + "var css='html,body{min-width:0!important;}"
                    + "@media(max-width:840px){html,body{overflow-x:hidden!important;}"
                    + "c-wiz,main,[role=main]{max-width:100%!important;min-width:0!important;}"
                    + "a[href*=\"/detail/\"]{max-width:100%!important;}}';"
                    + "function mount(){var head=document.head||document.documentElement;"
                    + "if(!document.getElementById('aero-cws')){var s=document.createElement('style');"
                    + "s.id='aero-cws';s.textContent=css;head.appendChild(s);}"
                    + "var meta=document.querySelector('meta[name=\"viewport\"]');"
                    + "if(!meta){meta=document.createElement('meta');meta.name='viewport';"
                    + "head.appendChild(meta);}meta.content='width=device-width,initial-scale=1';}"
                    + "mount();new MutationObserver(mount).observe(document.documentElement,"
                    + "{childList:true,subtree:true});})();";

    private LemurXWebStore() {}

    /** 只处理 https 的官方商店。看已提交的地址，不看导航开始时的地址。 */
    public static void fit(Tab tab) {
        if (tab == null) return;
        WebContents webContents = tab.getWebContents();
        if (webContents == null) return;
        GURL url = webContents.getLastCommittedUrl();
        if (!isStore(url)) return;
        // 带 Android 的标识会被商店转到“移动端不能用”。落到那一页就换回商店首页，并强制桌面标识。
        if ("/unsupported".equals(url.getPath())) {
            if (!sLeftUnsupported.add(tab.getId())) return;
            LoadUrlParams params = new LoadUrlParams(STORE_ROOT);
            params.setOverrideUserAgent(UserAgentOverrideOption.TRUE);
            params.setShouldReplaceCurrentEntry(true);
            tab.loadUrl(params);
            return;
        }
        sLeftUnsupported.remove(tab.getId());
        if (!TabUtils.isUsingDesktopUserAgent(webContents)) {
            TabUtils.switchUserAgent(tab, /* switchToDesktop= */ true);
            return;
        }
        webContents.evaluateJavaScript(LAYOUT, null);
    }

    private static boolean isStore(GURL url) {
        if (url == null || !url.isValid() || !"https".equals(url.getScheme())) return false;
        if (!url.getUsername().isEmpty() || !url.getPassword().isEmpty()) return false;
        String port = url.getPort();
        if (!port.isEmpty() && !"443".equals(port)) return false;
        String host = url.getHost();
        String path = url.getPath();
        if ("chromewebstore.google.com".equals(host)) return true;
        return "chrome.google.com".equals(host)
                && ("/webstore".equals(path) || path.startsWith("/webstore/"));
    }
}
