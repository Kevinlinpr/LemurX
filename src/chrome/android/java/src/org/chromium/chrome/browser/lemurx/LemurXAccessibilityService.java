// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import org.chromium.base.Log;

/**
 * 跨 App 自动化的系统入口：一个 {@link AccessibilityService}。
 *
 * <p>这是普通 App 唯一能合法拿到「整机 UI 树 + 点击/手势注入 + 全局返回/桌面 + 整屏截图」的
 * 途径，等价于 adb 的 {@code uiautomator dump} + {@code input tap} + {@code screencap}，
 * 但不需要 shell 权限，只需要用户在系统设置里授一次权。
 *
 * <p>本类只做两件事：把自己交给 {@link LemurXSystemHost}，把系统事件转过去。所有对 Lua 暴露的
 * 逻辑都在 SystemHost 里，保持这个类小到不会出错——服务一旦崩，系统会把无障碍开关整个关掉。
 *
 * <p>用户始终赢：这个服务默认不开；开了之后，任何时候都能在系统设置里关掉，LemurX 不会自己
 * 重新拉起它。Lua 总开关关掉后 SystemHost 不再向 Lua 派发任何事件、不再接受任何调用。
 */
public class LemurXAccessibilityService extends AccessibilityService {
    private static final String TAG = "LemurXA11y";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "connected");
        LemurXSystemHost.onServiceConnected(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        try {
            LemurXSystemHost.onAccessibilityEvent(event);
        } catch (Throwable t) {
            // 事件回调里任何异常都会让系统 kill 服务并关掉开关，这里必须兜住。
            Log.i(TAG, "event: %s", t.toString());
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "unbind");
        LemurXSystemHost.onServiceDisconnected(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        LemurXSystemHost.onServiceDisconnected(this);
        super.onDestroy();
    }
}
