// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import org.chromium.base.Log;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.firstrun.FirstRunStatus;

/**
 * Chromium 外壳上的几个 LemurX 决策点，由 patches/ 里的小钩子调进来。
 *
 * <p>只放「Chromium 问 LemurX 该怎么办」的纯函数：新标签页开什么、要不要走首次启动引导、
 * 要不要弹启动期的推广。真正的功能（对话控制台、Agent 设备桥）在 {@link LemurXConsoleShell}
 * 和 {@code lemurx.agent} 包里。
 */
public class LemurXChromeHost {
    private static final String TAG = "LemurX";

    // ---- 新标签页覆盖 ----
    // ChromeTabCreator.launchNtp 会打开这个 URL 而不是原生 NTP（见 patches/
    // ..._tabmodel_ChromeTabCreator.java.patch）。进程内存态；由控制台外壳在挂接时设置。
    private static volatile @Nullable String sNewTabUrl;

    static void setNewTabUrl(@Nullable String url) {
        sNewTabUrl = (url == null || url.isEmpty()) ? null : url;
        // 新标签页地址同时就是 OpenMuse 控制台外壳的首页。
        if (sNewTabUrl != null) {
            LemurXConsoleShell.setHomeUrl(sNewTabUrl);
        }
    }

    /** 供 ChromeTabCreator 调用：null 表示用原生新标签页。 */
    public static @Nullable String newTabUrlOverride() {
        // 外壳启用时，新标签页就是 Agent 界面（chrome-native://lemurx/chat/<新 id>，
        // 见 LemurXConsoleShell.launchUrl），没登录也一样；外壳关掉才用这里设的地址 / 原生 NTP。
        if (LemurXConsoleShell.isEnabled()) {
            return LemurXConsoleShell.launchUrl();
        }
        return sNewTabUrl;
    }

    // ---- 首次启动 ----
    // LemurX 不走 Chromium 的 First Run Experience（Chromium logo + “Add account to device” 的
    // Google 账号同步页、搜索引擎/默认浏览器引导）。账号登录是 LemurX 自己的 Google 登录（设置页
    // 底栏，见 LemurXSettingsFragment），和 Chrome 的同步账号无关。
    // 见 patches/..._firstrun_FirstRunFlowSequencer.java.patch。

    /**
     * 供 FirstRunFlowSequencer.checkIfFirstRunIsNecessary 调用：true 表示 LemurX 接管了首次启动，
     * FRE 一律跳过。顺手把 FRE 标成已完成（SharedPreferences，不依赖 native；EULA 的 native pref 由
     * FirstRunUtils.cacheFirstRunPrefs 在 native 起来后按“flow complete”补上），这样所有依赖
     * FirstRunStatus.getFirstRunFlowComplete() 的功能（CCT、promo 门控、指标）都按老用户处理。
     */
    public static boolean skipFirstRun() {
        try {
            if (!FirstRunStatus.getFirstRunFlowComplete()) {
                FirstRunStatus.setFirstRunFlowComplete(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "mark first run complete failed", e);
        }
        return true;
    }

    /**
     * 供 TabbedRootUiCoordinator.maybeShowRequiredPromptsAndPromos 调用：true 表示 LemurX 不要
     * 启动时的非上下文弹窗（“LemurX notifications make things easier” 通知权限引导、默认浏览器 /
     * 登录 / 同步等 startup promo）。崩溃恢复对话框和搜索引擎选择（监管要求）不受影响；下载、无痕等
     * 场景触发的上下文通知权限请求（contextual=true）也照常。
     */
    public static boolean suppressStartupPrompts() {
        return true;
    }
}
